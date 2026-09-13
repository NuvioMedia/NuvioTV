package seek

import (
	"encoding/binary"
)

const mp4BoxHeaderSize = 8

var (
	moovType = [4]byte{'m', 'o', 'o', 'v'}
	mvhdType = [4]byte{'m', 'v', 'h', 'd'}
)

func durationFromMP4(data []byte) (durationSec float64, ok bool) {
	for len(data) >= mp4BoxHeaderSize {
		size := binary.BigEndian.Uint32(data)
		typ := [4]byte{data[4], data[5], data[6], data[7]}
		payloadSize := int64(size) - mp4BoxHeaderSize
		if payloadSize < 0 {
			return 0, false
		}
		if size == 1 {

			if len(data) < 8+8 {
				return 0, false
			}
			size64 := binary.BigEndian.Uint64(data[8:])
			payloadSize = int64(size64) - 8 - mp4BoxHeaderSize
			if payloadSize < 0 {
				return 0, false
			}
			data = data[16:]
		} else {
			data = data[mp4BoxHeaderSize:]
		}
		boxEnd := int64(len(data))
		if payloadSize > boxEnd {

			if typ == moovType {
				return 0, false
			}
			return 0, false
		}
		switch typ {
		case moovType:
			return parseMvhdFrom(data[:payloadSize])
		default:
			data = data[payloadSize:]
		}
	}
	return 0, false
}

func parseMvhdFrom(moovPayload []byte) (durationSec float64, ok bool) {
	data := moovPayload
	for len(data) >= mp4BoxHeaderSize {
		size := binary.BigEndian.Uint32(data)
		typ := [4]byte{data[4], data[5], data[6], data[7]}
		payloadSize := int64(size) - mp4BoxHeaderSize
		if payloadSize < 0 {
			return 0, false
		}
		if size == 1 {
			if len(data) < 16 {
				return 0, false
			}
			size64 := binary.BigEndian.Uint64(data[8:])
			payloadSize = int64(size64) - 8 - mp4BoxHeaderSize
			if payloadSize < 0 {
				return 0, false
			}
			data = data[16:]
		} else {
			data = data[mp4BoxHeaderSize:]
		}
		if int64(len(data)) < payloadSize {
			return 0, false
		}
		if typ != mvhdType {
			data = data[payloadSize:]
			continue
		}

		if payloadSize < 8 {
			return 0, false
		}
		version := data[0]
		timescale := binary.BigEndian.Uint32(data[4:8])
		if timescale == 0 {
			return 0, false
		}
		var duration uint64
		if version == 1 {
			if payloadSize < 16 {
				return 0, false
			}
			duration = binary.BigEndian.Uint64(data[8:16])
		} else {
			duration = uint64(binary.BigEndian.Uint32(data[8:12]))
		}
		return float64(duration) / float64(timescale), true
	}
	return 0, false
}
