package vpn

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	tls "github.com/refraction-networking/utls"
	"openconnect-worker/auth"
	"openconnect-worker/base"
	"openconnect-worker/session"
	"openconnect-worker/utils"
)

var (
	cstpReqHeaders = make(map[string]string)
)

func init() {
	cstpReqHeaders["X-CSTP-VPNAddress-Type"] = "IPv4"
	cstpReqHeaders["X-CSTP-MTU"] = "1360"
	cstpReqHeaders["X-CSTP-Base-MTU"] = "1360"
}

func initTunnelParams() {
	sessionToken := session.Sess.SessionToken
	if sessionToken == "" && auth.WebVpnCookie != "" {
		sessionToken = auth.WebVpnCookie
		session.Sess.SessionToken = sessionToken
	}
	cstpReqHeaders["Cookie"] = "webvpn=" + sessionToken
	cstpReqHeaders["X-CSTP-Local-VPNAddress-IP4"] = base.LocalInterface.Ip4

	session.Sess.PreMasterSecret, _ = utils.MakeMasterSecret()
	cstpReqHeaders["X-DTLS-Master-Secret"] = hex.EncodeToString(session.Sess.PreMasterSecret)
	cstpReqHeaders["X-DTLS12-CipherSuite"] = "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:AES128-GCM-SHA256"
	base.Debug("CSTP init params: Cookie=webvpn=" + sessionToken)
}

// SetupTunnel executes AnyConnect HTTP CONNECT command to establish CSTP and DTLS channels
func SetupTunnel(protectFn utils.ProtectSocketFn) (*session.ConnSession, error) {
	initTunnelParams()

	connectUrl := auth.Prof.Scheme + auth.Prof.HostWithPort + "/CSCOSSLC/tunnel"
	req, err := http.NewRequest("CONNECT", connectUrl, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create CONNECT request: %w", err)
	}

	if auth.Prof.SNI != "" {
		if auth.Prof.Port != 0 && auth.Prof.Port != 443 {
			req.Host = fmt.Sprintf("%s:%d", auth.Prof.SNI, auth.Prof.Port)
		} else {
			req.Host = auth.Prof.SNI
		}
	}
	base.Info("CONNECT", connectUrl, "Host:", req.Host)

	utils.SetCommonHeader(req)
	for k, v := range cstpReqHeaders {
		req.Header[k] = []string{v}
	}

	if auth.Conn != nil {
		err = req.Write(auth.Conn)
	}

	if auth.Conn == nil || err != nil {
		if auth.Conn != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
		}
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()

		serverName := auth.Prof.SNI
		if serverName == "" {
			serverName = auth.Prof.Host
		}
		auth.Conn, err = utils.DialBrowserTLS(ctx, auth.Prof.HostWithPort, serverName, base.Cfg.InsecureSkipVerify, protectFn)
		if err != nil {
			return nil, fmt.Errorf("failed to connect for CSTP tunnel: %w", err)
		}
		auth.BufR = bufio.NewReader(auth.Conn)
		err = req.Write(auth.Conn)
		if err != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
			return nil, fmt.Errorf("failed to send CONNECT: %w", err)
		}
	}

	resp, err := http.ReadResponse(auth.BufR, req)
	if err != nil {
		if auth.Conn != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
		}
		return nil, fmt.Errorf("failed to read CONNECT response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		var bodyStr string
		if resp.Body != nil {
			bodyBytes, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
			bodyStr = string(bodyBytes)
			_ = resp.Body.Close()
		}
		base.Error(fmt.Sprintf("CSTP tunnel negotiation failed! Status: %s (code %d). Headers: %v. Body: %s",
			resp.Status, resp.StatusCode, resp.Header, bodyStr))

		if auth.Conn != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
		}
		return nil, fmt.Errorf("CSTP tunnel negotiation failed with status: %s (code %d): %s", resp.Status, resp.StatusCode, bodyStr)
	}

	if base.Cfg.LogLevel == "Debug" {
		buf := bytes.NewBuffer(nil)
		_ = resp.Header.Write(buf)
		base.Debug("CSTP response headers:\n", buf.String())
	}

	cSess := session.Sess.NewConnSession(&resp.Header)
	remoteAddr := auth.Conn.RemoteAddr().String()
	cSess.ServerAddress = strings.Split(remoteAddr, ":")[0]
	cSess.Hostname = auth.Prof.Host
	cSess.TLSCipherSuite = tls.CipherSuiteName(auth.Conn.ConnectionState().CipherSuite)

	base.Info("CSTP tunnel established: Server IP", cSess.ServerAddress, "VPN Address", cSess.VPNAddress, "MTU", cSess.MTU)

	// Launch TLS transport loop in background
	go TlsChannel(auth.Conn, auth.BufR, cSess, resp)

	// Launch secondary DTLS UDP transport if enabled and server supports it
	if !base.Cfg.NoDTLS && cSess.DTLSPort != "" {
		go DtlsChannel(cSess, protectFn)
	}

	cSess.DPDTimer()
	cSess.ReadDeadTimer()

	return cSess, nil
}
