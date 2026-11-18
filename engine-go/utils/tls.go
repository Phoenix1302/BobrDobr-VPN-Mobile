package utils

import (
	"context"
	"fmt"
	"net"
	"syscall"
	"time"

	tls "github.com/refraction-networking/utls"
)

// ProtectSocketFn is invoked synchronously on raw socket descriptor before connect
type ProtectSocketFn func(socketFd int) bool

// DialBrowserTLS establishes a TLS connection using uTLS with Google Chrome fingerprint,
// custom ALPN strictly set to ["http/1.1"], and synchronous socket protection hook.
func DialBrowserTLS(ctx context.Context, targetAddr string, serverName string, insecure bool, protectFn ProtectSocketFn) (*tls.UConn, error) {
	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			if protectFn != nil {
				var protectErr error
				err := c.Control(func(fd uintptr) {
					if !protectFn(int(fd)) {
						protectErr = fmt.Errorf("protect_callback failed for socket fd %d", fd)
					}
				})
				if err != nil {
					return fmt.Errorf("socket control failed: %w", err)
				}
				if protectErr != nil {
					return protectErr
				}
			}
			return nil
		},
	}

	rawConn, err := dialer.DialContext(ctx, "tcp", targetAddr)
	if err != nil {
		return nil, fmt.Errorf("tcp dial failed: %w", err)
	}

	sni := serverName
	if host, _, err := net.SplitHostPort(serverName); err == nil {
		sni = host
	}

	config := &tls.Config{
		ServerName:         sni,
		InsecureSkipVerify: insecure,
	}

	uConn := tls.UClient(rawConn, config, tls.HelloCustom)

	// Fetch Chrome fingerprint specification (GREASE, CipherSuites, Curves, Extensions)
	spec, err := tls.UTLSIdToSpec(tls.HelloChrome_Auto)
	if err != nil {
		rawConn.Close()
		return nil, fmt.Errorf("failed to generate chrome spec: %w", err)
	}

	// Override ALPN strictly to ["http/1.1"] to guarantee CSTP AnyConnect protocol compatibility
	alpnReplaced := false
	for i, ext := range spec.Extensions {
		if _, ok := ext.(*tls.ALPNExtension); ok {
			spec.Extensions[i] = &tls.ALPNExtension{
				AlpnProtocols: []string{"http/1.1"},
			}
			alpnReplaced = true
			break
		}
	}
	if !alpnReplaced {
		spec.Extensions = append(spec.Extensions, &tls.ALPNExtension{
			AlpnProtocols: []string{"http/1.1"},
		})
	}

	if err := uConn.ApplyPreset(&spec); err != nil {
		rawConn.Close()
		return nil, fmt.Errorf("failed to apply chrome preset: %w", err)
	}

	if err := uConn.HandshakeContext(ctx); err != nil {
		uConn.Close()
		return nil, fmt.Errorf("tls handshake failed: %w", err)
	}

	return uConn, nil
}
