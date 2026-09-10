package seek

import (
	"bytes"
	"encoding/binary"
	"math"
)

var (
	ebmlSegmentID = []byte{0x18, 0x53, 0x80, 0x67}

	ebmlInfoID = []byte{0x15, 0x49, 0xA9, 0x66}

	ebmlTimestampScaleID = []byte{0x2A, 0xD7, 0xB1}

	ebmlDurationID = []byte{0x44, 0x89}
)

const defaultTimestampScale = 1000000

func durationFromMKV(data []byte) (durationSec float64, ok bool) {

	segStart := findInBytes(data, ebmlSegmentID)
	if segStart < 0 {
		return 0, false
	}
	segData := data[segStart:]

	off, payloadLen := readEBMLElement(segData)
	if off < 0 || payloadLen < 0 {
		return 0, false
	}
	segPayload := segData[off:]
	if int64(len(segPayload)) >= payloadLen {
		segPayload = segPayload[:payloadLen]
	}

	infoStart := findInBytes(segPayload, ebmlInfoID)
	if infoStart < 0 {
		return 0, false
	}
	infoData := segPayload[infoStart:]
	off2, infoPayloadLen := readEBMLElement(infoData)
	if off2 < 0 {
		return 0, false
	}
	infoPayload := infoData[off2:]
	if int64(len(infoPayload)) > infoPayloadLen {
		infoPayload = infoPayload[:infoPayloadLen]
	}
	var timecodeScale uint64 = defaultTimestampScale
	var duration float64 = -1

	for len(infoPayload) > 0 {
		idLen, _ := readEBMLVINT(infoPayload)
		if idLen <= 0 {
			break
		}
		idBytes := infoPayload[:idLen]
		rest := infoPayload[idLen:]
		if len(rest) < 1 {
			break
		}
		sizeLen, size := readEBMLVINT(rest)
		if sizeLen <= 0 {
			break
		}
		payloadStart := sizeLen
		payloadEnd := payloadStart + int(size)
		if payloadEnd > len(rest) {
			payloadEnd = len(rest)
		}
		payload := rest[payloadStart:payloadEnd]
		switch {
		case bytesEqual(idBytes, ebmlTimestampScaleID):
			if len(payload) >= 1 && size <= 8 {
				timecodeScale = readEBMLUint(payload, int(size))
			}
		case bytesEqual(idBytes, ebmlDurationID):
			if size == 8 && len(payload) >= 8 {
				bits := binary.BigEndian.Uint64(payload)
				duration = math.Float64frombits(bits)
			}
		}
		infoPayload = rest[payloadEnd:]
	}
	if duration <= 0 || timecodeScale == 0 {
		return 0, false
	}

	return duration * float64(timecodeScale) / 1e9, true
}

func findInBytes(data, needle []byte) int {
	return bytes.Index(data, needle)
}

func bytesEqual(a, b []byte) bool {
	return bytes.Equal(a, b)
}

func readEBMLElement(data []byte) (payloadOffset int, payloadLen int64) {
	idLen, _ := readEBMLVINT(data)
	if idLen <= 0 || idLen >= len(data) {
		return -1, -1
	}
	sizeLen, size := readEBMLVINT(data[idLen:])
	if sizeLen <= 0 || idLen+sizeLen > len(data) {
		return -1, -1
	}
	return idLen + sizeLen, int64(size)
}

func readEBMLVINT(data []byte) (length int, value uint64) {
	if len(data) == 0 {
		return 0, 0
	}
	first := data[0]
	var numBytes int
	for i := 0; i < 8; i++ {
		if (first & (0x80 >> i)) != 0 {
			numBytes = i + 1
			break
		}
	}
	if numBytes == 0 || numBytes > len(data) {
		return 0, 0
	}
	mask := byte(0xFF >> numBytes)
	value = uint64(first & mask)
	for i := 1; i < numBytes; i++ {
		value = (value << 8) | uint64(data[i])
	}
	return numBytes, value
}

func readEBMLUint(data []byte, size int) uint64 {
	if size > len(data) || size > 8 {
		return 0
	}
	var v uint64
	for i := 0; i < size; i++ {
		v = (v << 8) | uint64(data[i])
	}
	return v
}
