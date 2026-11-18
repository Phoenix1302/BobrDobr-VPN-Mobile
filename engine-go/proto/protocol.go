package proto

// CSTP 8-byte header template:
// Bytes 0-3: 'S', 'T', 'F', 0x01
// Bytes 4-5: 16-bit payload length (big-endian)
// Byte 6: Payload type (0x00=DATA, 0x03=DPD-REQ, 0x04=DPD-RESP, 0x05=DISCONNECT, 0x07=KEEPALIVE)
// Byte 7: 0x00 (flags)
var Header = []byte{
	0x53, 0x54, 0x46, 0x01,
	0x00, 0x00,
	0x00,
	0x00,
}

// Payload buffer struct
type Payload struct {
	Type byte
	Data []byte
}
