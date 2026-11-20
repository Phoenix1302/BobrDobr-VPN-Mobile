package vpn

import (
	"context"
	"encoding/hex"
	"fmt"
	"net"
	"strconv"
	"syscall"
	"time"

	"github.com/pion/dtls/v3"
	"openconnect-worker/base"
	"openconnect-worker/proto"
	"openconnect-worker/session"
	"openconnect-worker/utils"
)

type SessionStore struct {
	sess dtls.Session
}

func (store *SessionStore) Set([]byte, dtls.Session) error {
	return nil
}

func (store *SessionStore) Get([]byte) (dtls.Session, error) {
	return store.sess, nil
}

func (store *SessionStore) Del([]byte) error {
	return nil
}

// DtlsChannel establishes and runs the secondary UDP DTLS transport channel
func DtlsChannel(cSess *session.ConnSession, protectFn utils.ProtectSocketFn) {
	var (
		conn          *dtls.Conn
		dSess         *session.DtlsSession
		err           error
		bytesReceived int
		dead          = time.Duration(cSess.DTLSDpdTime+5) * time.Second
	)

	defer func() {
		base.Info("DTLS channel exiting")
		if conn != nil {
			_ = conn.Close()
		}
		if dSess != nil {
			dSess.Close()
		}
	}()

	if dead < 15*time.Second {
		dead = 35 * time.Second
	}

	port, _ := strconv.Atoi(cSess.DTLSPort)
	addr := &net.UDPAddr{IP: net.ParseIP(cSess.ServerAddress), Port: port}
	id, _ := hex.DecodeString(cSess.DTLSId)

	config := &dtls.Config{
		InsecureSkipVerify:   true,
		ExtendedMasterSecret: dtls.DisableExtendedMasterSecret,
		CipherSuites: func() []dtls.CipherSuiteID {
			switch cSess.DTLSCipherSuite {
			case "ECDHE-ECDSA-AES128-GCM-SHA256":
				return []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256}
			case "ECDHE-RSA-AES128-GCM-SHA256":
				return []dtls.CipherSuiteID{dtls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256}
			case "ECDHE-ECDSA-AES256-GCM-SHA384":
				return []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384}
			case "ECDHE-RSA-AES256-GCM-SHA384":
				return []dtls.CipherSuiteID{dtls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384}
			default:
				return []dtls.CipherSuiteID{
					dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
					dtls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
					dtls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
					dtls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
				}
			}
		}(),
		SessionStore: &SessionStore{dtls.Session{ID: id, Secret: session.Sess.PreMasterSecret}},
	}

	// Create UDP socket with socket protector control hook
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			if protectFn != nil {
				var protectErr error
				ctrlErr := c.Control(func(fd uintptr) {
					if !protectFn(int(fd)) {
						protectErr = fmt.Errorf("protect_callback failed for DTLS UDP socket fd %d", fd)
					}
				})
				if ctrlErr != nil {
					return ctrlErr
				}
				if protectErr != nil {
					return protectErr
				}
			}
			return nil
		},
	}

	ctxListen, cancelListen := context.WithTimeout(context.Background(), 10*time.Second)
	network := "udp4"
	bindAddr := "0.0.0.0:0"
	if addr.IP != nil && addr.IP.To4() == nil {
		network = "udp6"
		bindAddr = "[::]:0"
	}
	packetConn, err := lc.ListenPacket(ctxListen, network, bindAddr)
	cancelListen()
	if err != nil {
		base.Error("Failed to bind protected DTLS socket:", err)
		close(cSess.DtlsSetupChan)
		return
	}

	conn, err = dtls.Client(packetConn, addr, config)
	if err != nil {
		base.Error("DTLS client creation error:", err)
		_ = packetConn.Close()
		close(cSess.DtlsSetupChan)
		return
	}

	ctxHandshake, cancelHandshake := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancelHandshake()

	if err = conn.HandshakeContext(ctxHandshake); err != nil {
		base.Error("DTLS handshake error:", err)
		close(cSess.DtlsSetupChan)
		return
	}

	cSess.DtlsConnected.Store(true)
	dSess = cSess.DSess
	close(cSess.DtlsSetupChan)

	state, success := conn.ConnectionState()
	if success {
		cSess.DTLSCipherSuite = dtls.CipherSuiteName(state.CipherSuiteID)
	}

	base.Info("DTLS channel negotiation succeeded (cipher:", cSess.DTLSCipherSuite, ")")

	go payloadOutDTLSToServer(conn, dSess, cSess)

	readBuf := make([]byte, BufferSize)

	for {
		if cSess.ResetDTLSReadDead.Load() {
			_ = conn.SetReadDeadline(time.Now().Add(dead))
			cSess.ResetDTLSReadDead.Store(false)
		}

		bytesReceived, err = conn.Read(readBuf)
		if err != nil {
			select {
			case <-dSess.CloseChan:
				return
			default:
				base.Info("DTLS read error:", err)
				return
			}
		}

		if bytesReceived <= 0 {
			continue
		}

		switch readBuf[0] {
		case 0x00: // DATA
			pl := GetPayloadBuffer()
			payloadLen := bytesReceived - 1
			if payloadLen > cap(pl.Data) {
				pl.Data = make([]byte, payloadLen)
			} else {
				pl.Data = pl.Data[:payloadLen]
			}
			copy(pl.Data, readBuf[1:bytesReceived])

			select {
			case cSess.PayloadIn <- pl:
			case <-dSess.CloseChan:
				PutPayloadBuffer(pl)
				return
			}
		case 0x03: // DPD-REQ
			respPl := &proto.Payload{
				Type: 0x04,
				Data: make([]byte, 0, 1),
			}
			select {
			case cSess.PayloadOutDTLS <- respPl:
			case <-dSess.CloseChan:
			}
		case 0x04: // DPD-RESP
		case 0x05: // DISCONNECT
			base.Info("DTLS received DISCONNECT")
			return
		case 0x07: // KEEPALIVE
		}
	}
}

func payloadOutDTLSToServer(conn *dtls.Conn, dSess *session.DtlsSession, cSess *session.ConnSession) {
	defer func() {
		base.Info("DTLS payloadOut to server exiting")
		_ = conn.Close()
		dSess.Close()
	}()

	buf := make([]byte, BufferSize)

	for {
		var pl *proto.Payload
		select {
		case pl = <-cSess.PayloadOutDTLS:
		case <-dSess.CloseChan:
			return
		}

		if pl == nil {
			continue
		}

		var writeData []byte
		if pl.Type == 0x00 {
			needed := len(pl.Data) + 1
			if needed > len(buf) {
				writeData = make([]byte, needed)
			} else {
				writeData = buf[:needed]
			}
			writeData[0] = 0x00
			copy(writeData[1:], pl.Data)
		} else {
			writeData = []byte{pl.Type}
		}

		_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		_, err := conn.Write(writeData)
		PutPayloadBuffer(pl)

		if err != nil {
			base.Error("DTLS write error:", err)
			return
		}
	}
}
