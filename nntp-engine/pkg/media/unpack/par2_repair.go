package unpack

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"streamnzb/pkg/core/logger"
)

const (
	maxRepairVolumeBytes = 128 << 20 // 128 MB ceiling for in-memory Volume 1 repair
	par2PacketHeaderSize = 64
	maxPar2ReadBytes     = 64 << 20 // 64 MB guardrail
	gf16Poly             = 0x1100b  // GF(2^16) generator polynomial x^16 + x^12 + x^3 + x + 1
	gf16Gen              = 5        // PAR2 generator g = 5
	gf16Mod              = 65535    // 2^16 - 1
)

var (
	par2PacketMagic      = []byte("PAR2\x00PKT")
	par2FileDescPacket   = []byte("PAR 2.0\x00FileDesc")
	par2MainPacketHeader = []byte("PAR 2.0\x00Main\x00\x00\x00\x00")
	par2RecvPacketHeader = []byte("PAR 2.0\x00RecvSlic")

	gf16InitOnce sync.Once
	gf16Log      [65536]uint16
	gf16ALog     [65536]uint16
)

func gf16MulRaw(a, b uint32) uint32 {
	var res uint32
	for i := 0; i < 16; i++ {
		if (b & (1 << i)) != 0 {
			res ^= (a << i)
		}
	}
	for i := 31; i >= 16; i-- {
		if (res & (1 << i)) != 0 {
			res ^= (gf16Poly << (i - 16))
		}
	}
	return res
}

func initGF16() {
	gf16InitOnce.Do(func() {
		var x uint32 = 1
		for i := 0; i < gf16Mod; i++ {
			gf16ALog[i] = uint16(x)
			gf16Log[uint16(x)] = uint16(i)
			x = gf16MulRaw(x, gf16Gen)
		}
		gf16ALog[gf16Mod] = gf16ALog[0]
	})
}

func gf16Add(a, b uint16) uint16 {
	return a ^ b
}

func gf16Mul(a, b uint16) uint16 {
	if a == 0 || b == 0 {
		return 0
	}
	gf16InitOnce.Do(initGF16)
	sum := int(gf16Log[a]) + int(gf16Log[b])
	if sum >= gf16Mod {
		sum -= gf16Mod
	}
	return gf16ALog[sum]
}

func gf16Div(a, b uint16) uint16 {
	if a == 0 {
		return 0
	}
	if b == 0 {
		panic("gf16 divide by zero")
	}
	gf16InitOnce.Do(initGF16)
	diff := int(gf16Log[a]) - int(gf16Log[b])
	if diff < 0 {
		diff += gf16Mod
	}
	return gf16ALog[diff]
}

func gf16Pow(base uint16, exp uint32) uint16 {
	if exp == 0 {
		return 1
	}
	if base == 0 {
		return 0
	}
	gf16InitOnce.Do(initGF16)
	expMod := uint64(exp) % uint64(gf16Mod)
	prod := (uint64(gf16Log[base]) * expMod) % uint64(gf16Mod)
	return gf16ALog[prod]
}

type par2FileMeta struct {
	fileID [16]byte
	name   string
	length int64
	// hashMD5 covers the whole file; hash16k covers its first 16 KiB (or the
	// whole file when it is smaller). hash16k is what identifies a file without
	// reading it: see resolveNamesFromPAR2.
	hashMD5    [16]byte
	hash16k    [16]byte
	sliceStart int
	sliceCount int
}

type par2SetMeta struct {
	sliceSize  int64
	files      []*par2FileMeta
	recvSlices map[uint32][]byte // exponent -> raw recovery slice data
}

// RepairFirstVolumeWithPAR2 attempts targeted in-memory PAR2 reconstruction of a missing
// Volume 1 archive (.rar / .part01.rar) when continuation volumes exist.
func RepairFirstVolumeWithPAR2(ctx context.Context, files []UnpackableFile) (UnpackableFile, error) {
	if err := contextErr(ctx); err != nil {
		return nil, err
	}

	par2Files := collectPAR2Files(files)
	if len(par2Files) == 0 {
		return nil, errors.New("no PAR2 files found")
	}

	meta, err := parsePAR2Metadata(ctx, par2Files)
	if err != nil {
		return nil, fmt.Errorf("failed to parse PAR2 metadata: %w", err)
	}

	if meta.sliceSize <= 0 || len(meta.files) == 0 {
		return nil, errors.New("invalid PAR2 metadata: missing slice size or files")
	}

	targetFile := findMissingFirstVolumeMeta(meta, files)
	if targetFile == nil {
		return nil, errors.New("no missing first volume identified in PAR2 set")
	}

	if targetFile.length > maxRepairVolumeBytes {
		return nil, fmt.Errorf("missing first volume %s is too large for targeted in-memory repair (%d > %d MB limit)", targetFile.name, targetFile.length, maxRepairVolumeBytes>>20)
	}

	logger.Info("Targeted Volume 1 PAR2 repair starting",
		"name", targetFile.name,
		"length", targetFile.length,
		"slice_size", meta.sliceSize,
		"missing_slices", targetFile.sliceCount,
		"available_recv_slices", len(meta.recvSlices))

	if len(meta.recvSlices) < targetFile.sliceCount {
		return nil, fmt.Errorf("insufficient PAR2 recovery slices (%d available, %d required): %w", len(meta.recvSlices), targetFile.sliceCount, ErrPAR2RepairRequired)
	}

	repairedData, err := reconstructVolumeSlices(ctx, files, meta, targetFile)
	if err != nil {
		return nil, fmt.Errorf("PAR2 slice reconstruction failed for %s: %w", targetFile.name, err)
	}

	// Verify reconstructed MD5 if available
	if targetFile.hashMD5 != [16]byte{} {
		sum := md5.Sum(repairedData)
		if sum != targetFile.hashMD5 {
			return nil, fmt.Errorf("reconstructed Volume 1 %s checksum mismatch: %w", targetFile.name, ErrPAR2RepairRequired)
		}
	}

	vfPart := virtualPart{
		VirtualStart: 0,
		VirtualEnd:   targetFile.length,
		VolFile:      &byteBufferUnpackableFile{name: targetFile.name, data: repairedData},
		VolOffset:    0,
	}

	repairedFile := NewVirtualFile(targetFile.name, targetFile.length, []virtualPart{vfPart})
	logger.Info("Targeted Volume 1 PAR2 repair complete", "name", targetFile.name, "bytes", targetFile.length)
	return repairedFile, nil
}

func collectPAR2Files(files []UnpackableFile) []UnpackableFile {
	var par2s []UnpackableFile
	for _, f := range files {
		if f == nil {
			continue
		}
		name := strings.ToLower(ExtractFilename(f.Name()))
		if strings.HasSuffix(name, ExtPar2) {
			par2s = append(par2s, f)
		}
	}
	sort.SliceStable(par2s, func(i, j int) bool {
		return par2s[i].Size() < par2s[j].Size()
	})
	return par2s
}

func parsePAR2Metadata(ctx context.Context, par2Files []UnpackableFile) (*par2SetMeta, error) {
	meta := &par2SetMeta{
		recvSlices: make(map[uint32][]byte),
	}
	fileMap := make(map[[16]byte]*par2FileMeta)
	var fileOrder [][16]byte

	for _, f := range par2Files {
		if err := contextErr(ctx); err != nil {
			return nil, err
		}
		stream, err := f.OpenStreamCtx(ctx)
		if err != nil {
			continue
		}
		raw, err := io.ReadAll(io.LimitReader(stream, maxPar2ReadBytes))
		stream.Close()
		if err != nil && len(raw) == 0 {
			continue
		}

		for off := 0; off+par2PacketHeaderSize <= len(raw); {
			if !bytes.Equal(raw[off:off+8], par2PacketMagic) {
				off++
				continue
			}
			packetLen := binary.LittleEndian.Uint64(raw[off+8 : off+16])
			if packetLen < par2PacketHeaderSize {
				off++
				continue
			}
			end := off + int(packetLen)
			if end > len(raw) {
				break
			}
			packetType := raw[off+48 : off+64]
			body := raw[off+par2PacketHeaderSize : end]

			switch {
			case bytes.Equal(packetType, par2MainPacketHeader):
				if len(body) >= 12 {
					meta.sliceSize = int64(binary.LittleEndian.Uint64(body[0:8]))
					numFiles := binary.LittleEndian.Uint32(body[8:12])
					if len(body) >= 12+int(numFiles)*16 {
						fileOrder = fileOrder[:0]
						for i := 0; i < int(numFiles); i++ {
							var id [16]byte
							copy(id[:], body[12+i*16:12+(i+1)*16])
							fileOrder = append(fileOrder, id)
						}
					}
				}
			case bytes.Equal(packetType, par2FileDescPacket):
				if len(body) >= 56 {
					var id [16]byte
					copy(id[:], body[0:16])
					fm, ok := fileMap[id]
					if !ok {
						fm = &par2FileMeta{fileID: id}
						fileMap[id] = fm
					}
					copy(fm.hashMD5[:], body[16:32])
					copy(fm.hash16k[:], body[32:48])
					fm.length = int64(binary.LittleEndian.Uint64(body[48:56]))
					nameRaw := bytes.TrimRight(body[56:], "\x00")
					fm.name = filepath.Base(string(bytes.ToValidUTF8(nameRaw, nil)))
				}
			case bytes.Equal(packetType, par2RecvPacketHeader):
				if len(body) >= 4 {
					exponent := binary.LittleEndian.Uint32(body[0:4])
					sliceData := body[4:]
					if _, exists := meta.recvSlices[exponent]; !exists && len(sliceData) > 0 {
						meta.recvSlices[exponent] = bytes.Clone(sliceData)
					}
				}
			}
			off = end
		}
	}

	if len(fileOrder) > 0 {
		for _, id := range fileOrder {
			if fm, ok := fileMap[id]; ok {
				meta.files = append(meta.files, fm)
			}
		}
	} else {
		for _, fm := range fileMap {
			meta.files = append(meta.files, fm)
		}
	}

	if meta.sliceSize > 0 {
		currentSlice := 0
		for _, fm := range meta.files {
			fm.sliceStart = currentSlice
			if fm.length > 0 {
				fm.sliceCount = int((fm.length + meta.sliceSize - 1) / meta.sliceSize)
			}
			currentSlice += fm.sliceCount
		}
	}

	return meta, nil
}

func findMissingFirstVolumeMeta(meta *par2SetMeta, presentFiles []UnpackableFile) *par2FileMeta {
	presentNames := make(map[string]bool)
	for _, f := range presentFiles {
		if f != nil {
			presentNames[strings.ToLower(ExtractFilename(f.Name()))] = true
		}
	}

	for _, fm := range meta.files {
		lowerName := strings.ToLower(fm.name)
		if presentNames[lowerName] {
			continue
		}
		// Match canonical first volume extensions
		if (strings.HasSuffix(lowerName, ExtRar) && !strings.Contains(lowerName, ".part")) ||
			strings.Contains(lowerName, ".part001.") || strings.Contains(lowerName, ".part01.") ||
			strings.Contains(lowerName, ".part1.") || strings.HasSuffix(lowerName, ".r00") {
			return fm
		}
	}
	return nil
}

func reconstructVolumeSlices(ctx context.Context, presentFiles []UnpackableFile, meta *par2SetMeta, targetFile *par2FileMeta) ([]byte, error) {
	sliceSize := meta.sliceSize
	numMissing := targetFile.sliceCount
	if numMissing <= 0 {
		return nil, errors.New("target file has 0 slices")
	}

	// Select required PAR2 recovery slices
	var sortedExponents []uint32
	for exp := range meta.recvSlices {
		sortedExponents = append(sortedExponents, exp)
	}
	sort.Slice(sortedExponents, func(i, j int) bool { return sortedExponents[i] < sortedExponents[j] })
	if len(sortedExponents) < numMissing {
		return nil, fmt.Errorf("need %d recovery slices, have %d", numMissing, len(sortedExponents))
	}
	selectedExponents := sortedExponents[:numMissing]

	// Map present files to their slice ranges
	presentMap := make(map[string]UnpackableFile)
	for _, f := range presentFiles {
		if f != nil {
			presentMap[strings.ToLower(ExtractFilename(f.Name()))] = f
		}
	}

	totalSlices := 0
	for _, fm := range meta.files {
		totalSlices += fm.sliceCount
	}

	// Build adjusted recovery vectors: R'_i = R_i XOR (sum over intact slices j of v_{i,j} * D_j)
	adjustedRecv := make([][]uint16, numMissing)
	wordsPerSlice := int(sliceSize / 2)

	for i, exp := range selectedExponents {
		if err := contextErr(ctx); err != nil {
			return nil, err
		}
		rawSlice := meta.recvSlices[exp]
		sliceWords := make([]uint16, wordsPerSlice)
		for w := 0; w < wordsPerSlice && w*2+1 < len(rawSlice); w++ {
			sliceWords[w] = binary.LittleEndian.Uint16(rawSlice[w*2 : w*2+2])
		}
		adjustedRecv[i] = sliceWords
	}

	buf := make([]byte, sliceSize)
	for _, fm := range meta.files {
		if fm.fileID == targetFile.fileID {
			continue // missing file
		}
		pf, ok := presentMap[strings.ToLower(fm.name)]
		if !ok {
			return nil, fmt.Errorf("additional missing file %s in PAR2 set", fm.name)
		}

		for s := 0; s < fm.sliceCount; s++ {
			if err := contextErr(ctx); err != nil {
				return nil, err
			}
			sliceGlobalIdx := fm.sliceStart + s
			fileOffset := int64(s) * sliceSize

			n, err := pf.ReadAt(buf, fileOffset)
			if err != nil && n == 0 {
				return nil, fmt.Errorf("failed to read slice %d from %s: %w", s, fm.name, err)
			}
			clear(buf[n:])

			// Convert slice buffer to uint16 words and subtract (XOR) contribution
			for w := 0; w < wordsPerSlice; w++ {
				val := binary.LittleEndian.Uint16(buf[w*2 : w*2+2])
				if val == 0 {
					continue
				}
				for i, exp := range selectedExponents {
					factor := gf16Pow(gf16Gen, exp*uint32(sliceGlobalIdx))
					adjustedRecv[i][w] ^= gf16Mul(factor, val)
				}
			}
		}
	}

	// Construct K x K encoding matrix V_{i, m} = g^(exp_i * targetSlice_m)
	matrix := make([][]uint16, numMissing)
	for i, exp := range selectedExponents {
		row := make([]uint16, numMissing)
		for m := 0; m < numMissing; m++ {
			globalIdx := targetFile.sliceStart + m
			row[m] = gf16Pow(gf16Gen, exp*uint32(globalIdx))
		}
		matrix[i] = row
	}

	invMatrix, err := gf16InvertMatrix(matrix)
	if err != nil {
		return nil, fmt.Errorf("failed to invert PAR2 reconstruction matrix: %w", err)
	}

	reconstructedBytes := make([]byte, targetFile.length)
	for m := 0; m < numMissing; m++ {
		reconstructedSlice := make([]uint16, wordsPerSlice)
		for i := 0; i < numMissing; i++ {
			coeff := invMatrix[m][i]
			if coeff == 0 {
				continue
			}
			for w := 0; w < wordsPerSlice; w++ {
				reconstructedSlice[w] ^= gf16Mul(coeff, adjustedRecv[i][w])
			}
		}

		startByte := int64(m) * sliceSize
		endByte := startByte + sliceSize
		if endByte > targetFile.length {
			endByte = targetFile.length
		}

		for w := 0; w < wordsPerSlice && startByte+int64(w*2+1) <= targetFile.length; w++ {
			val := reconstructedSlice[w]
			idx := startByte + int64(w*2)
			reconstructedBytes[idx] = byte(val)
			if idx+1 < targetFile.length {
				reconstructedBytes[idx+1] = byte(val >> 8)
			}
		}
	}

	return reconstructedBytes, nil
}

func gf16InvertMatrix(matrix [][]uint16) ([][]uint16, error) {
	n := len(matrix)
	if n == 0 {
		return nil, errors.New("empty matrix")
	}
	aug := make([][]uint16, n)
	for i := 0; i < n; i++ {
		aug[i] = make([]uint16, 2*n)
		copy(aug[i][:n], matrix[i])
		aug[i][n+i] = 1
	}

	for i := 0; i < n; i++ {
		pivotRow := -1
		for r := i; r < n; r++ {
			if aug[r][i] != 0 {
				pivotRow = r
				break
			}
		}
		if pivotRow == -1 {
			return nil, errors.New("singular PAR2 matrix")
		}
		if pivotRow != i {
			aug[i], aug[pivotRow] = aug[pivotRow], aug[i]
		}

		pivotVal := aug[i][i]
		for c := 0; c < 2*n; c++ {
			aug[i][c] = gf16Div(aug[i][c], pivotVal)
		}

		for r := 0; r < n; r++ {
			if r == i {
				continue
			}
			factor := aug[r][i]
			if factor == 0 {
				continue
			}
			for c := 0; c < 2*n; c++ {
				aug[r][c] ^= gf16Mul(factor, aug[i][c])
			}
		}
	}

	inv := make([][]uint16, n)
	for i := 0; i < n; i++ {
		inv[i] = make([]uint16, n)
		copy(inv[i], aug[i][n:])
	}
	return inv, nil
}

type byteBufferUnpackableFile struct {
	name string
	data []byte
}

type par2NopReadSeekCloser struct {
	*bytes.Reader
}

func (n *par2NopReadSeekCloser) Close() error { return nil }

func (f *byteBufferUnpackableFile) Name() string            { return f.name }
func (f *byteBufferUnpackableFile) Size() int64             { return int64(len(f.data)) }
func (f *byteBufferUnpackableFile) EnsureSegmentMap() error { return nil }
func (f *byteBufferUnpackableFile) OpenStream() (io.ReadSeekCloser, error) {
	return &par2NopReadSeekCloser{bytes.NewReader(f.data)}, nil
}
func (f *byteBufferUnpackableFile) OpenStreamCtx(ctx context.Context) (io.ReadSeekCloser, error) {
	return &par2NopReadSeekCloser{bytes.NewReader(f.data)}, nil
}
func (f *byteBufferUnpackableFile) OpenReaderAt(ctx context.Context, offset int64) (io.ReadCloser, error) {
	if offset < 0 || offset >= int64(len(f.data)) {
		return io.NopCloser(bytes.NewReader(nil)), nil
	}
	return io.NopCloser(bytes.NewReader(f.data[offset:])), nil
}
func (f *byteBufferUnpackableFile) ReadAt(p []byte, off int64) (int, error) {
	if off < 0 || off >= int64(len(f.data)) {
		return 0, io.EOF
	}
	n := copy(p, f.data[off:])
	if n < len(p) {
		return n, io.EOF
	}
	return n, nil
}
