package vpn

import (
	"bufio"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"time"

	"openconnect-worker/base"
	"openconnect-worker/proto"
	"openconnect-worker/session"
)

// TlsChannel processes CSTP stream from server over TLS
func TlsChannel(conn net.Conn, bufR *bufio.Reader, cSess *session.ConnSession, resp *http.Response) {
	defer func() {
		base.Info("TLS CSTP channel exiting")
		if resp != nil && resp.Body != nil {
			_ = resp.Body.Close()
		}
		_ = conn.Close()
		cSess.Close()
	}()

	dead := time.Duration(cSess.TLSDpdTime+5) * time.Second
	if dead < 15*time.Second {
		dead = 35 * time.Second
	}

	go PayloadOutTLSToServer(conn, cSess)

	hdrBuf := make([]byte, 8)

	for {
		if cSess.ResetTLSReadDead.Load() {
			_ = conn.SetReadDeadline(time.Now().Add(dead))
			cSess.ResetTLSReadDead.Store(false)
		}

		// Read 8-byte CSTP header: STF\x01 (4B) + length (2B) + type (1B) + flags (1B)
		_, err := io.ReadFull(bufR, hdrBuf)
		if err != nil {
			select {
			case <-cSess.CloseChan:
				return
			default:
				base.Info("TLS CSTP read header error:", err)
				return
			}
		}

		if hdrBuf[0] != 0x53 || hdrBuf[1] != 0x54 || hdrBuf[2] != 0x46 || hdrBuf[3] != 0x01 {
			base.Warn("Invalid CSTP header magic:", hdrBuf[:4])
			return
		}

		dataLen := binary.BigEndian.Uint16(hdrBuf[4:6])
		pktType := hdrBuf[6]

		pl := GetPayloadBuffer()
		if int(dataLen) > cap(pl.Data) {
			pl.Data = make([]byte, dataLen)
		} else {
			pl.Data = pl.Data[:dataLen]
		}

		if dataLen > 0 {
			_, err = io.ReadFull(bufR, pl.Data)
			if err != nil {
				PutPayloadBuffer(pl)
				base.Info("TLS CSTP read payload error:", err)
				return
			}
		}

		switch pktType {
		case 0x00: // DATA
			pl.Type = 0x00
			select {
			case cSess.PayloadIn <- pl:
			case <-cSess.CloseChan:
				PutPayloadBuffer(pl)
				return
			}
		case 0x03: // DPD-REQ -> respond with DPD-RESP (0x04)
			PutPayloadBuffer(pl)
			respPl := &proto.Payload{
				Type: 0x04,
				Data: make([]byte, 0, 8),
			}
			select {
			case cSess.PayloadOutTLS <- respPl:
			case <-cSess.CloseChan:
				return
			}
		case 0x04: // DPD-RESP
			PutPayloadBuffer(pl)
		case 0x05: // DISCONNECT from server
			PutPayloadBuffer(pl)
			base.Info("Received CSTP BYE (0x05) from server; terminating session")
			return
		case 0x07: // KEEPALIVE
			PutPayloadBuffer(pl)
		default:
			PutPayloadBuffer(pl)
		}
	}
}

// PayloadOutTLSToServer transmits queued packets from TUN to server over TLS
func PayloadOutTLSToServer(conn net.Conn, cSess *session.ConnSession) {
	defer func() {
		base.Info("TLS payloadOut to server exiting")
		cSess.SendBye(conn)
		_ = conn.Close()
		cSess.Close()
	}()

	hdr := make([]byte, 8)
	copy(hdr[:4], proto.Header[:4])

	for {
		var pl *proto.Payload
		select {
		case pl = <-cSess.PayloadOutTLS:
		case <-cSess.CloseChan:
			return
		}

		if pl == nil {
			continue
		}

		l := len(pl.Data)
		binary.BigEndian.PutUint16(hdr[4:6], uint16(l))
		hdr[6] = pl.Type
		hdr[7] = 0x00

		cSess.WriteMu.Lock()
		_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		// Write 8-byte header
		_, err := conn.Write(hdr)
		if err != nil {
			cSess.WriteMu.Unlock()
			PutPayloadBuffer(pl)
			base.Error("TLS write header error:", err)
			return
		}

		// Write payload if present
		if l > 0 {
			_, err = conn.Write(pl.Data)
			if err != nil {
				cSess.WriteMu.Unlock()
				PutPayloadBuffer(pl)
				base.Error("TLS write payload error:", err)
				return
			}
		}
		cSess.WriteMu.Unlock()

		PutPayloadBuffer(pl)
	}
}
