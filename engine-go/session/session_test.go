package session

import (
	"encoding/binary"
	"net"
	"testing"
	"time"

	"openconnect-worker/proto"
)

func TestSendByePacket(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()

	sess := &Session{}
	cSess := &ConnSession{
		Sess: sess,
	}

	readDone := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 1024)
		_ = serverConn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := serverConn.Read(buf)
		if err != nil {
			t.Errorf("server read error: %v", err)
			readDone <- nil
			return
		}
		readDone <- buf[:n]
	}()

	cSess.SendBye(clientConn)

	packet := <-readDone
	if packet == nil {
		t.Fatal("no packet received from SendBye")
	}

	if len(packet) < 8 {
		t.Fatalf("packet too short: len=%d", len(packet))
	}

	// Verify magic "STF\x01"
	if string(packet[:4]) != string(proto.Header[:4]) {
		t.Errorf("expected magic STF\\x01, got %v", packet[:4])
	}

	payloadLen := binary.BigEndian.Uint16(packet[4:6])
	pktType := packet[6]
	if pktType != 0x05 {
		t.Errorf("expected packet type 0x05 (DISCONNECT/BYE), got 0x%02x", pktType)
	}

	payload := packet[8 : 8+payloadLen]
	if len(payload) == 0 || payload[0] != 0xb0 {
		t.Errorf("expected reason code 0xb0, got %v", payload)
	}

	reasonStr := string(payload[1:])
	if reasonStr != "Client disconnect" {
		t.Errorf("expected reason 'Client disconnect', got '%s'", reasonStr)
	}

	t.Logf("SendBye packet validated successfully! (type=0x%02x, len=%d, reason='%s')", pktType, payloadLen, reasonStr)
}
