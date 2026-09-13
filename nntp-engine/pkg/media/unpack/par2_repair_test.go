package unpack

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/binary"
	"testing"
)

func TestGF16Arithmetic(t *testing.T) {
	initGF16()

	// Test 1: Addition (XOR)
	if got := gf16Add(0x1234, 0x1234); got != 0 {
		t.Fatalf("gf16Add(0x1234, 0x1234) = %x, want 0", got)
	}

	// Test 2: Multiplication identity
	if got := gf16Mul(0xABCD, 1); got != 0xABCD {
		t.Fatalf("gf16Mul(0xABCD, 1) = %x, want 0xABCD", got)
	}

	// Test 3: Multiplication & Division roundtrip
	a := uint16(0x5A5A)
	b := uint16(0x1234)
	prod := gf16Mul(a, b)
	quot := gf16Div(prod, b)
	if quot != a {
		t.Fatalf("gf16Div(gf16Mul(%x, %x), %x) = %x, want %x", a, b, b, quot, a)
	}

	// Test 4: Power
	if got := gf16Pow(5, 0); got != 1 {
		t.Fatalf("gf16Pow(5, 0) = %d, want 1", got)
	}
}

func TestGF16MatrixInversion(t *testing.T) {
	// 2x2 matrix
	m := [][]uint16{
		{gf16Pow(5, 0), gf16Pow(5, 0)},
		{gf16Pow(5, 1), gf16Pow(5, 2)},
	}

	inv, err := gf16InvertMatrix(m)
	if err != nil {
		t.Fatalf("gf16InvertMatrix failed: %v", err)
	}

	// Check V * V^-1 = I
	res00 := gf16Add(gf16Mul(m[0][0], inv[0][0]), gf16Mul(m[0][1], inv[1][0]))
	res01 := gf16Add(gf16Mul(m[0][0], inv[0][1]), gf16Mul(m[0][1], inv[1][1]))
	if res00 != 1 || res01 != 0 {
		t.Fatalf("matrix multiplication check failed: res00=%d res01=%d", res00, res01)
	}
}

func TestRepairFirstVolumeWithPAR2Synthetic(t *testing.T) {
	// Slice size 64 bytes
	sliceSize := int64(64)
	vol1Data := bytes.Repeat([]byte("A"), 64)
	vol2Data := bytes.Repeat([]byte("B"), 64)

	// File IDs
	vol1ID := [16]byte{1, 1, 1, 1}
	vol2ID := [16]byte{2, 2, 2, 2}
	vol1MD5 := md5.Sum(vol1Data)

	// Build PAR2 packets
	var par2Buf bytes.Buffer

	// 1. Main Packet
	var mainBody bytes.Buffer
	var sliceSzBytes [8]byte
	binary.LittleEndian.PutUint64(sliceSzBytes[:], uint64(sliceSize))
	mainBody.Write(sliceSzBytes[:])
	var numFilesBytes [4]byte
	binary.LittleEndian.PutUint32(numFilesBytes[:], 2)
	mainBody.Write(numFilesBytes[:])
	mainBody.Write(vol1ID[:])
	mainBody.Write(vol2ID[:])

	writePAR2Packet(&par2Buf, par2MainPacketHeader, mainBody.Bytes())

	// 2. FileDesc Packet for Vol 1
	var desc1Body bytes.Buffer
	desc1Body.Write(vol1ID[:])
	desc1Body.Write(vol1MD5[:])
	desc1Body.Write(make([]byte, 16)) // 16k MD5
	var len1Bytes [8]byte
	binary.LittleEndian.PutUint64(len1Bytes[:], uint64(len(vol1Data)))
	desc1Body.Write(len1Bytes[:])
	desc1Body.WriteString("test_release.part01.rar\x00")

	writePAR2Packet(&par2Buf, par2FileDescPacket, desc1Body.Bytes())

	// 3. FileDesc Packet for Vol 2
	var desc2Body bytes.Buffer
	desc2Body.Write(vol2ID[:])
	desc2Body.Write(make([]byte, 32))
	var len2Bytes [8]byte
	binary.LittleEndian.PutUint64(len2Bytes[:], uint64(len(vol2Data)))
	desc2Body.Write(len2Bytes[:])
	desc2Body.WriteString("test_release.part02.rar\x00")

	writePAR2Packet(&par2Buf, par2FileDescPacket, desc2Body.Bytes())

	// 4. Recovery Slice for Exponent 0
	// Recovery slice R_0 = g^(0*0)*vol1 + g^(0*1)*vol2 = 1*vol1 XOR 1*vol2
	recvData := make([]byte, sliceSize)
	for i := 0; i < int(sliceSize); i++ {
		recvData[i] = vol1Data[i] ^ vol2Data[i]
	}

	var recvBody bytes.Buffer
	var expBytes [4]byte
	binary.LittleEndian.PutUint32(expBytes[:], 0)
	recvBody.Write(expBytes[:])
	recvBody.Write(recvData)

	writePAR2Packet(&par2Buf, par2RecvPacketHeader, recvBody.Bytes())

	// Set up present files: ONLY vol2 and par2 are present; vol1 is MISSING!
	files := []UnpackableFile{
		&memoryUnpackableFile{name: "test_release.part02.rar", data: vol2Data},
		&memoryUnpackableFile{name: "test_release.par2", data: par2Buf.Bytes()},
	}

	repairedFile, err := RepairFirstVolumeWithPAR2(context.Background(), files)
	if err != nil {
		t.Fatalf("RepairFirstVolumeWithPAR2 failed: %v", err)
	}

	if repairedFile == nil {
		t.Fatal("expected repaired UnpackableFile, got nil")
	}

	if repairedFile.Name() != "test_release.part01.rar" {
		t.Fatalf("expected repaired filename test_release.part01.rar, got %s", repairedFile.Name())
	}

	if repairedFile.Size() != int64(len(vol1Data)) {
		t.Fatalf("expected repaired size %d, got %d", len(vol1Data), repairedFile.Size())
	}

	stream, err := repairedFile.OpenStream()
	if err != nil {
		t.Fatalf("failed to open repaired stream: %v", err)
	}
	defer stream.Close()

	readBuf := make([]byte, len(vol1Data))
	if _, err := stream.Read(readBuf); err != nil {
		t.Fatalf("failed to read repaired stream: %v", err)
	}

	if !bytes.Equal(readBuf, vol1Data) {
		t.Fatalf("reconstructed data mismatch: got %s, want %s", string(readBuf), string(vol1Data))
	}
}

func writePAR2Packet(buf *bytes.Buffer, packetType []byte, body []byte) {
	packetLen := uint64(64 + len(body))
	buf.Write([]byte("PAR2\x00PKT"))

	var lenBytes [8]byte
	binary.LittleEndian.PutUint64(lenBytes[:], packetLen)
	buf.Write(lenBytes[:])

	// Hash MD5 of body
	hash := md5.Sum(body)
	buf.Write(hash[:])
	buf.Write(make([]byte, 16)) // SetID dummy
	buf.Write(packetType)
	buf.Write(body)
}
