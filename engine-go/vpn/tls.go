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

// TlsChannel processes CSTP stream from server over TLS using the buffered reader
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

	for {
		if cSess.ResetTLSReadDead.Load() {
			_ = conn.SetReadDeadline(time.Now().Add(dead))
			cSess.ResetTLSReadDead.Store(false)
		}

		var header [8]byte
		_, err := io.ReadFull(bufR, header[:])
		if err != nil {
			select {
			case <-cSess.CloseChan:
				return
			default:
				base.Info("TLS CSTP read header error:", err)
				return
			}
		}

		if header[0] != 0x53 || header[1] != 0x54 || header[2] != 0x46 || header[3] != 0x01 {
			base.Warn("Invalid CSTP header magic:", header[:4])
			continue
		}

		dataLen := binary.BigEndian.Uint16(header[4:6])
		pktType := header[6]

		pl := GetPayloadBuffer()
		if cap(pl.Data) < int(dataLen) {
			pl.Data = make([]byte, dataLen)
		} else {
			pl.Data = pl.Data[:dataLen]
		}

		if dataLen > 0 {
			_, err = io.ReadFull(bufR, pl.Data)
			if err != nil {
				PutPayloadBuffer(pl)
				select {
				case <-cSess.CloseChan:
					return
				default:
					base.Error("TLS CSTP read payload error:", err)
					return
				}
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

// PayloadOutTLSToServer transmits queued packets from TUN to server over TLS in atomic single-record frames
func PayloadOutTLSToServer(conn net.Conn, cSess *session.ConnSession) {
	defer func() {
		base.Info("TLS payloadOut to server exiting")
		cSess.SendBye(conn)
		_ = conn.Close()
		cSess.Close()
	}()

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
		var writeBuf []byte
		if pl.Type == 0x00 {
			// Prepend 8-byte CSTP header into a single buffer so GnuTLS / ocserv receives
			// the header and packet in a single TLS application data record.
			if cap(pl.Data) >= l+8 && len(pl.Data) > 0 {
				writeBuf = pl.Data[:l+8]
				copy(writeBuf[8:], pl.Data[:l])
			} else {
				writeBuf = make([]byte, l+8)
				copy(writeBuf[8:], pl.Data)
			}
			copy(writeBuf[:4], proto.Header[:4])
			binary.BigEndian.PutUint16(writeBuf[4:6], uint16(l))
			writeBuf[6] = 0x00
			writeBuf[7] = 0x00
		} else {
			writeBuf = make([]byte, 8+len(pl.Data))
			copy(writeBuf[:4], proto.Header[:4])
			binary.BigEndian.PutUint16(writeBuf[4:6], uint16(len(pl.Data)))
			writeBuf[6] = pl.Type
			writeBuf[7] = 0x00
			if len(pl.Data) > 0 {
				copy(writeBuf[8:], pl.Data)
			}
		}

		cSess.WriteMu.Lock()
		_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		bytesSent, err := conn.Write(writeBuf)
		cSess.WriteMu.Unlock()
		PutPayloadBuffer(pl)

		if err != nil {
			base.Error("TLS write error:", err)
			return
		}
		_ = bytesSent
	}
}
