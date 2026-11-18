package vpn

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
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
	cstpReqHeaders["Cookie"] = "webvpn=" + session.Sess.SessionToken
	cstpReqHeaders["X-CSTP-Local-VPNAddress-IP4"] = base.LocalInterface.Ip4

	session.Sess.PreMasterSecret, _ = utils.MakeMasterSecret()
	cstpReqHeaders["X-DTLS-Master-Secret"] = hex.EncodeToString(session.Sess.PreMasterSecret)
	cstpReqHeaders["X-DTLS12-CipherSuite"] = "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:AES128-GCM-SHA256"
}

// SetupTunnel executes AnyConnect HTTP CONNECT command to establish CSTP and DTLS channels
func SetupTunnel(protectFn utils.ProtectSocketFn) (*session.ConnSession, error) {
	initTunnelParams()

	connectUrl := auth.Prof.Scheme + auth.Prof.HostWithPort + "/CSCOSSLC/tunnel"
	if auth.Prof.SecretKey != "" {
		if strings.Contains(connectUrl, "?") {
			connectUrl += "&" + auth.Prof.SecretKey
		} else {
			connectUrl += "?" + auth.Prof.SecretKey
		}
	}

	req, err := http.NewRequest("CONNECT", connectUrl, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create CONNECT request: %w", err)
	}

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
		}
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()

		auth.Conn, err = utils.DialBrowserTLS(ctx, auth.Prof.HostWithPort, auth.Prof.Host, base.Cfg.InsecureSkipVerify, protectFn)
		if err != nil {
			return nil, fmt.Errorf("failed to connect for CSTP tunnel: %w", err)
		}
		auth.BufR = bufio.NewReader(auth.Conn)
		err = req.Write(auth.Conn)
		if err != nil {
			_ = auth.Conn.Close()
			return nil, fmt.Errorf("failed to send CONNECT: %w", err)
		}
	}

	resp, err := http.ReadResponse(auth.BufR, req)
	if err != nil {
		if auth.Conn != nil {
			_ = auth.Conn.Close()
		}
		return nil, fmt.Errorf("failed to read CONNECT response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		if auth.Conn != nil {
			_ = auth.Conn.Close()
		}
		return nil, fmt.Errorf("CSTP tunnel negotiation failed with status: %s", resp.Status)
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
