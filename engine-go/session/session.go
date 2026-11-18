package session

import (
	"encoding/binary"
	"net"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"openconnect-worker/base"
	"openconnect-worker/proto"
	"openconnect-worker/utils"
)

var (
	Sess = &Session{}
)

type Session struct {
	SessionToken    string
	PreMasterSecret []byte

	ActiveClose bool
	CloseChan   chan struct{}
	CSess       *ConnSession
}

type ConnSession struct {
	Sess *Session

	ServerAddress string
	LocalAddress  string
	Hostname      string
	TunName       string
	VPNAddress    string
	VPNMask       string
	DNS           []string
	MTU           int
	SplitInclude  []string
	SplitExclude  []string

	TLSCipherSuite    string
	TLSDpdTime        int
	TLSKeepaliveTime  int
	DTLSPort          string
	DTLSDpdTime       int
	DTLSKeepaliveTime int
	DTLSId            string
	DTLSCipherSuite   string

	closeOnce      sync.Once
	byeOnce        sync.Once
	CloseChan      chan struct{}
	PayloadIn      chan *proto.Payload
	PayloadOutTLS  chan *proto.Payload
	PayloadOutDTLS chan *proto.Payload

	DtlsConnected atomic.Bool
	DtlsSetupChan chan struct{}
	DSess         *DtlsSession

	ResetTLSReadDead  atomic.Bool
	ResetDTLSReadDead atomic.Bool
}

type DtlsSession struct {
	closeOnce sync.Once
	CloseChan chan struct{}
}

func (sess *Session) NewConnSession(header *http.Header) *ConnSession {
	cSess := &ConnSession{
		Sess:          sess,
		LocalAddress:  base.LocalInterface.Ip4,
		CloseChan:     make(chan struct{}),
		DtlsSetupChan: make(chan struct{}),
		PayloadIn:     make(chan *proto.Payload, 256),
		PayloadOutTLS: make(chan *proto.Payload, 256),
		PayloadOutDTLS: make(chan *proto.Payload, 256),
		DSess: &DtlsSession{
			CloseChan: make(chan struct{}),
		},
	}
	cSess.DtlsConnected.Store(false)
	cSess.ResetTLSReadDead.Store(true)
	cSess.ResetDTLSReadDead.Store(true)

	sess.CSess = cSess
	sess.ActiveClose = false
	sess.CloseChan = make(chan struct{})

	cSess.VPNAddress = header.Get("X-CSTP-Address")
	cSess.VPNMask = header.Get("X-CSTP-Netmask")
	cSess.MTU, _ = strconv.Atoi(header.Get("X-CSTP-MTU"))
	if cSess.MTU <= 0 {
		cSess.MTU = 1360
	}
	cSess.DNS = header.Values("X-CSTP-DNS")
	cSess.SplitInclude = header.Values("X-CSTP-Split-Include")
	cSess.SplitExclude = header.Values("X-CSTP-Split-Exclude")

	cSess.TLSDpdTime, _ = strconv.Atoi(header.Get("X-CSTP-DPD"))
	cSess.TLSKeepaliveTime, _ = strconv.Atoi(header.Get("X-CSTP-Keepalive"))

	cSess.DTLSId = header.Get("X-DTLS-Session-ID")
	if cSess.DTLSId == "" {
		cSess.DTLSId = header.Get("X-DTLS-App-ID")
	}
	cSess.DTLSPort = header.Get("X-DTLS-Port")
	cSess.DTLSDpdTime, _ = strconv.Atoi(header.Get("X-DTLS-DPD"))
	cSess.DTLSKeepaliveTime, _ = strconv.Atoi(header.Get("X-DTLS-Keepalive"))
	if base.Cfg.NoDTLS {
		cSess.DTLSCipherSuite = "Unknown"
	} else {
		cSess.DTLSCipherSuite = header.Get("X-DTLS12-CipherSuite")
	}

	return cSess
}

func (cSess *ConnSession) DPDTimer() {
	go func() {
		dpdTime := utils.Min(cSess.TLSDpdTime, cSess.DTLSDpdTime) - 5
		if dpdTime < 10 {
			dpdTime = 15
		}
		ticker := time.NewTicker(time.Duration(dpdTime) * time.Second)
		defer ticker.Stop()

		tlsDpd := proto.Payload{
			Type: 0x03,
			Data: make([]byte, 0, 8),
		}
		dtlsDpd := proto.Payload{
			Type: 0x03,
			Data: make([]byte, 0, 1),
		}

		for {
			select {
			case <-ticker.C:
				select {
				case cSess.PayloadOutTLS <- &tlsDpd:
				default:
				}
				if cSess.DtlsConnected.Load() {
					select {
					case cSess.PayloadOutDTLS <- &dtlsDpd:
					default:
					}
				}
			case <-cSess.CloseChan:
				return
			}
		}
	}()
}

func (cSess *ConnSession) ReadDeadTimer() {
	go func() {
		ticker := time.NewTicker(4 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			select {
			case <-cSess.CloseChan:
				return
			default:
				cSess.ResetTLSReadDead.Store(true)
				cSess.ResetDTLSReadDead.Store(true)
			}
		}
	}()
}

func (cSess *ConnSession) Close() {
	cSess.closeOnce.Do(func() {
		if cSess.DtlsConnected.Load() {
			cSess.DSess.Close()
		}
		close(cSess.CloseChan)
		Sess.CSess = nil

		select {
		case <-Sess.CloseChan:
		default:
			close(Sess.CloseChan)
		}
	})
}

// SendBye transmits Cisco AnyConnect CSTP DISCONNECT (0x05) frame to terminate the server session cleanly
func (cSess *ConnSession) SendBye(conn net.Conn) {
	if conn == nil {
		return
	}
	cSess.byeOnce.Do(func() {
		base.Info("Sending CSTP BYE frame (0x05): Client disconnect")
		reasonBytes := []byte("Client disconnect")
		payload := append([]byte{0xb0}, reasonBytes...)
		length := uint16(len(payload))
		byePacket := make([]byte, 8+len(payload))
		copy(byePacket[:4], proto.Header[:4]) // "STF\x01"
		binary.BigEndian.PutUint16(byePacket[4:6], length)
		byePacket[6] = 0x05 // DISCONNECT
		byePacket[7] = 0x00
		copy(byePacket[8:], payload)
		_ = conn.SetWriteDeadline(time.Now().Add(1500 * time.Millisecond))
		_, _ = conn.Write(byePacket)
		base.Info("CSTP BYE frame transmitted successfully")
	})
}

func (dSess *DtlsSession) Close() {
	dSess.closeOnce.Do(func() {
		close(dSess.CloseChan)
		if Sess.CSess != nil {
			Sess.CSess.DtlsConnected.Store(false)
			Sess.CSess.DTLSCipherSuite = ""
		}
	})
}
