//go:build android || linux

package tun

import (
	"os"
	"sync/atomic"

	"openconnect-worker/base"
	"openconnect-worker/session"
	"openconnect-worker/vpn"
)

var (
	StatsTxBytes   atomic.Uint64
	StatsTxPackets atomic.Uint64
	StatsRxBytes   atomic.Uint64
	StatsRxPackets atomic.Uint64
)

func ResetStats() {
	StatsTxBytes.Store(0)
	StatsTxPackets.Store(0)
	StatsRxBytes.Store(0)
	StatsRxPackets.Store(0)
}

// StartTunLoops initializes Android TUN packet I/O wrapping the provided tun_fd
func StartTunLoops(tunFile *os.File, cSess *session.ConnSession) {
	go tunToPayloadOut(tunFile, cSess)
	go payloadInToTun(tunFile, cSess)
}

// tunToPayloadOut reads raw IP packets from Android TUN interface and enqueues to TLS or DTLS
func tunToPayloadOut(tunFile *os.File, cSess *session.ConnSession) {
	defer func() {
		base.Info("TUN to PayloadOut reader loop exiting")
		cSess.Close()
	}()

	buf := make([]byte, 65535)

	for {
		n, err := tunFile.Read(buf)
		if err != nil {
			select {
			case <-cSess.CloseChan:
				return
			default:
				base.Info("TUN read error:", err)
				return
			}
		}

		if n <= 0 {
			continue
		}

		StatsTxBytes.Add(uint64(n))
		StatsTxPackets.Add(1)

		pl := vpn.GetPayloadBuffer()
		pl.Type = 0x00
		if n > cap(pl.Data) {
			pl.Data = make([]byte, n)
		} else {
			pl.Data = pl.Data[:n]
		}
		copy(pl.Data, buf[:n])

		if cSess.DtlsConnected.Load() {
			select {
			case cSess.PayloadOutDTLS <- pl:
			case <-cSess.DSess.CloseChan:
				// Fallback to TLS if DTLS drops
				select {
				case cSess.PayloadOutTLS <- pl:
				case <-cSess.CloseChan:
					vpn.PutPayloadBuffer(pl)
					return
				}
			case <-cSess.CloseChan:
				vpn.PutPayloadBuffer(pl)
				return
			}
		} else {
			select {
			case cSess.PayloadOutTLS <- pl:
			case <-cSess.CloseChan:
				vpn.PutPayloadBuffer(pl)
				return
			}
		}
	}
}

// payloadInToTun receives processed packets from TLS/DTLS and writes raw IP packets into Android TUN
func payloadInToTun(tunFile *os.File, cSess *session.ConnSession) {
	defer func() {
		base.Info("PayloadIn to TUN writer loop exiting")
		cSess.Close()
	}()

	for {
		select {
		case pl, ok := <-cSess.PayloadIn:
			if !ok || pl == nil {
				return
			}

			n, err := tunFile.Write(pl.Data)
			vpn.PutPayloadBuffer(pl)

			if err != nil {
				select {
				case <-cSess.CloseChan:
					return
				default:
					base.Info("TUN write error:", err)
					return
				}
			}

			if n > 0 {
				StatsRxBytes.Add(uint64(n))
				StatsRxPackets.Add(1)
			}

		case <-cSess.CloseChan:
			return
		}
	}
}
