package vpn

import (
	"sync"

	"openconnect-worker/proto"
)

const BufferSize = 4096

var pool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, BufferSize)
		pl := proto.Payload{
			Type: 0x00,
			Data: b,
		}
		return &pl
	},
}

func GetPayloadBuffer() *proto.Payload {
	return pool.Get().(*proto.Payload)
}

func PutPayloadBuffer(pl *proto.Payload) {
	if pl == nil || cap(pl.Data) != BufferSize {
		return
	}
	pl.Type = 0x00
	pl.Data = pl.Data[:BufferSize]
	pool.Put(pl)
}
