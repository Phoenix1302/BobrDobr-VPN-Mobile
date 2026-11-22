package vpn

import (
	"strings"
	"testing"

	"openconnect-worker/auth"
)

func TestCreateConnectRequest(t *testing.T) {
	auth.ResetProfile()
	auth.Prof.Scheme = "https://"
	auth.Prof.Host = "168.113.253.30"
	auth.Prof.Port = 443
	auth.Prof.HostWithPort = "168.113.253.30:443"
	auth.Prof.SNI = "bobr.netphoenix.ru"
	auth.Prof.SecretKey = "kurwa"
	auth.WebVpnCookie = "test_session_token_12345"

	headers := initTunnelParams()
	connectUrl := auth.Prof.Scheme + auth.Prof.HostWithPort + "/CSCOSSLC/tunnel"

	req, err := createConnectRequest(connectUrl, headers)
	if err != nil {
		t.Fatalf("createConnectRequest failed: %v", err)
	}

	// 1. Verify Method and Path
	if req.Method != "CONNECT" {
		t.Errorf("Expected method CONNECT, got %s", req.Method)
	}
	if req.URL.Path != "/CSCOSSLC/tunnel" {
		t.Errorf("Expected path /CSCOSSLC/tunnel, got %s", req.URL.Path)
	}
	if req.URL.Query().Encode() != "" {
		t.Errorf("Expected no query in canonical CONNECT URL, got %s", req.URL.Query().Encode())
	}

	// 2. Verify Host matches SNI
	if req.Host != "bobr.netphoenix.ru" {
		t.Errorf("Expected Host 'bobr.netphoenix.ru', got '%s'", req.Host)
	}

	// 3. Verify Content-Type is absent (CONNECT has no body)
	if ct := req.Header.Get("Content-Type"); ct != "" {
		t.Errorf("Expected Content-Type to be absent, but got '%s'", ct)
	}

	// 4. Verify Cookie header
	cookie := req.Header.Get("Cookie")
	if !strings.Contains(cookie, "webvpn=test_session_token_12345") {
		t.Errorf("Expected webvpn cookie in Cookie header, got '%s'", cookie)
	}

	// 5. Verify CSTP & DTLS headers
	if req.Header.Get("X-CSTP-Version") != "1" {
		t.Errorf("Expected X-CSTP-Version=1, got '%s'", req.Header.Get("X-CSTP-Version"))
	}
	if req.Header.Get("X-DTLS-Master-Secret") == "" {
		t.Errorf("Expected X-DTLS-Master-Secret to be present")
	}
}

func TestConnectRequestFallback(t *testing.T) {
	auth.ResetProfile()
	auth.Prof.Scheme = "https://"
	auth.Prof.Host = "vpn.example.com"
	auth.Prof.Port = 8443
	auth.Prof.HostWithPort = "vpn.example.com:8443"
	auth.Prof.SNI = "vpn.example.com"
	auth.Prof.SecretKey = "mysecret"

	headers := initTunnelParams()
	fallbackUrl := auth.Prof.Scheme + auth.Prof.HostWithPort + "/CSCOSSLC/tunnel?" + auth.Prof.SecretKey

	req, err := createConnectRequest(fallbackUrl, headers)
	if err != nil {
		t.Fatalf("createConnectRequest fallback failed: %v", err)
	}

	if req.URL.Path != "/CSCOSSLC/tunnel" {
		t.Errorf("Expected path /CSCOSSLC/tunnel, got %s", req.URL.Path)
	}
	if req.URL.RawQuery != "mysecret" {
		t.Errorf("Expected query 'mysecret', got '%s'", req.URL.RawQuery)
	}
	if req.Host != "vpn.example.com:8443" {
		t.Errorf("Expected Host 'vpn.example.com:8443', got '%s'", req.Host)
	}
}
