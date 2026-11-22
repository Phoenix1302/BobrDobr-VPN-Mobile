package vpn

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	tls "github.com/refraction-networking/utls"
	"openconnect-worker/auth"
	"openconnect-worker/base"
	"openconnect-worker/session"
	"openconnect-worker/utils"
)

func initTunnelParams() map[string]string {
	headers := map[string]string{
		"X-CSTP-Version":         "1",
		"X-CSTP-Protocol":        "Copyright (c) 2004 Cisco Systems, Inc.",
		"X-CSTP-VPNAddress-Type": "IPv4",
		"X-CSTP-MTU":             "1360",
		"X-CSTP-Base-MTU":        "1360",
	}
	sessionToken := session.Sess.SessionToken
	if sessionToken == "" && auth.WebVpnCookie != "" {
		sessionToken = auth.WebVpnCookie
		session.Sess.SessionToken = sessionToken
	}
	if sessionToken == "" {
		base.Error("Warning: sessionToken is empty during CSTP tunnel initialization!")
	}
	headers["Cookie"] = "webvpn=" + sessionToken
	if auth.Prof.LocalIP != "" {
		headers["X-CSTP-Local-VPNAddress-IP4"] = auth.Prof.LocalIP
	}

	session.Sess.PreMasterSecret, _ = utils.MakeMasterSecret()
	headers["X-DTLS-Master-Secret"] = hex.EncodeToString(session.Sess.PreMasterSecret)
	headers["X-DTLS12-CipherSuite"] = "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:AES128-GCM-SHA256"
	base.Debug("CSTP init params: Cookie present=", sessionToken != "", ", length=", len(sessionToken))
	return headers
}

func createConnectRequest(urlStr string, headers map[string]string) (*http.Request, error) {
	req, err := http.NewRequest("CONNECT", urlStr, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create CONNECT request: %w", err)
	}

	if auth.Prof.SNI != "" {
		if auth.Prof.Port != 0 && auth.Prof.Port != 443 {
			req.Host = fmt.Sprintf("%s:%d", auth.Prof.SNI, auth.Prof.Port)
		} else {
			req.Host = auth.Prof.SNI
		}
	} else if auth.Prof.Host != "" {
		if auth.Prof.Port != 0 && auth.Prof.Port != 443 {
			req.Host = fmt.Sprintf("%s:%d", auth.Prof.Host, auth.Prof.Port)
		} else {
			req.Host = auth.Prof.Host
		}
	}

	utils.SetCommonHeader(req)
	// CONNECT method carries no body payload; clean up Content-Type to conform to canonical RFC specifications
	req.Header.Del("Content-Type")

	for k, v := range headers {
		req.Header.Set(k, v)
	}

	return req, nil
}

func dialTunnelTLS(protectFn utils.ProtectSocketFn) (*tls.UConn, *bufio.Reader, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	serverName := auth.Prof.SNI
	if serverName == "" {
		serverName = auth.Prof.Host
	}
	conn, err := utils.DialBrowserTLS(ctx, auth.Prof.HostWithPort, serverName, base.Cfg.InsecureSkipVerify, protectFn)
	if err != nil {
		return nil, nil, err
	}
	bufR := bufio.NewReader(conn)
	return conn, bufR, nil
}

// SetupTunnel executes AnyConnect HTTP CONNECT command to establish CSTP and DTLS channels
func SetupTunnel(protectFn utils.ProtectSocketFn) (*session.ConnSession, error) {
	headers := initTunnelParams()

	// Canonical AnyConnect / OpenConnect CSTP tunnel endpoint path is strictly /CSCOSSLC/tunnel
	connectUrl := auth.Prof.Scheme + auth.Prof.HostWithPort + "/CSCOSSLC/tunnel"
	req, err := createConnectRequest(connectUrl, headers)
	if err != nil {
		return nil, err
	}
	base.Info("CONNECT", connectUrl, "Host:", req.Host)

	var resp *http.Response
	reusedConn := auth.Conn != nil

	if reusedConn {
		err = req.Write(auth.Conn)
		if err == nil {
			resp, err = http.ReadResponse(auth.BufR, req)
		}
		if err != nil {
			base.Warn("Failed to establish CSTP over reused TLS session:", err, "- falling back to fresh TLS connection")
			_ = auth.Conn.Close()
			auth.Conn = nil
			auth.BufR = nil
		}
	}

	if auth.Conn == nil {
		auth.Conn, auth.BufR, err = dialTunnelTLS(protectFn)
		if err != nil {
			return nil, fmt.Errorf("failed to connect for CSTP tunnel: %w", err)
		}
		if err = req.Write(auth.Conn); err != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
			return nil, fmt.Errorf("failed to send CONNECT: %w", err)
		}
		resp, err = http.ReadResponse(auth.BufR, req)
		if err != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
			return nil, fmt.Errorf("failed to read CONNECT response: %w", err)
		}
	}

	// Fallback retry with camouflage secret if a custom reverse proxy rejects standard /CSCOSSLC/tunnel
	if (resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusMethodNotAllowed) && auth.Prof.SecretKey != "" {
		if resp.Body != nil {
			_ = resp.Body.Close()
		}
		if auth.Conn != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
		}
		base.Info("Standard CSTP path returned", resp.StatusCode, "- attempting retry with camouflage secret query")
		fallbackUrl := auth.Prof.Scheme + auth.Prof.HostWithPort + "/CSCOSSLC/tunnel?" + auth.Prof.SecretKey
		req2, err2 := createConnectRequest(fallbackUrl, headers)
		if err2 == nil {
			conn2, bufR2, dialErr := dialTunnelTLS(protectFn)
			if dialErr == nil {
				if writeErr := req2.Write(conn2); writeErr == nil {
					resp2, readErr := http.ReadResponse(bufR2, req2)
					if readErr == nil {
						auth.Conn = conn2
						auth.BufR = bufR2
						resp = resp2
						req = req2
					} else {
						_ = conn2.Close()
					}
				} else {
					_ = conn2.Close()
				}
			}
		}
	}
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
	if host, _, err := net.SplitHostPort(remoteAddr); err == nil {
		cSess.ServerAddress = host
	} else {
		cSess.ServerAddress = remoteAddr
	}
	cSess.Hostname = auth.Prof.Host
	cSess.TLSCipherSuite = tls.CipherSuiteName(auth.Conn.ConnectionState().CipherSuite)

	base.Info("CSTP tunnel established: Server IP", cSess.ServerAddress, "VPN Address", cSess.VPNAddress, "MTU", cSess.MTU)

	// Launch TLS transport loop in background using the authenticated buffered reader
	go TlsChannel(auth.Conn, auth.BufR, cSess, resp)

	// Launch secondary DTLS UDP transport if enabled and server supports it
	if !base.Cfg.NoDTLS && cSess.DTLSPort != "" {
		go DtlsChannel(cSess, protectFn)
	}

	cSess.DPDTimer()
	cSess.ReadDeadTimer()

	return cSess, nil
}
