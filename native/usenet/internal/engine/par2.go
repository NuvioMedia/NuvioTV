package engine

// PAR2 name recovery for fully obfuscated releases. When every subject (and
// often the yEnc name too) is a random hash, RAR4 volumes cannot be ordered:
// their headers carry no volume number. The release's PAR2 index still lists
// each file's real name, length and the MD5 of its first 16 KiB, so files are
// matched by content, never by position, and renamed before selection is
// retried. This only runs after a normal selection failed, and costs one
// article per candidate file, read a few at a time.

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/binary"
	"errors"
	"io"
	"path"
	"sort"
	"strings"
	"sync"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/rarname"
	"github.com/javi11/nntppool/v4"
)

var (
	par2Magic        = []byte("PAR2\x00PKT")
	par2FileDescType = []byte("PAR 2.0\x00FileDesc")
)

const (
	par2Hash16k        = 16 << 10
	par2IndexMaxBytes  = 32 << 20
	par2MaxCandidates  = 1000
	par2ReadConcurrent = 8
)

type par2File struct {
	name    string
	size    int64
	hash16k [md5.Size]byte
}

// parsePAR2 returns the file descriptions of a PAR2 file body. Packets whose
// own MD5 does not match are skipped, so a damaged index only loses entries.
func parsePAR2(b []byte) []par2File {
	var out []par2File
	seen := map[[16]byte]bool{}
	for off := 0; off+64 <= len(b); {
		if !bytes.Equal(b[off:off+8], par2Magic) {
			next := bytes.Index(b[off+1:], par2Magic)
			if next < 0 {
				break
			}
			off += 1 + next
			continue
		}
		length := binary.LittleEndian.Uint64(b[off+8:])
		if length < 64 || length%4 != 0 || length > uint64(len(b)-off) {
			off += len(par2Magic)
			continue
		}
		packet := b[off : off+int(length)]
		if sum := md5.Sum(packet[32:]); !bytes.Equal(sum[:], packet[16:32]) {
			off += len(par2Magic)
			continue
		}
		if body := packet[64:]; bytes.Equal(packet[48:64], par2FileDescType) && len(body) > 56 {
			var id [16]byte
			copy(id[:], body[:16])
			if !seen[id] {
				seen[id] = true
				f := par2File{size: int64(binary.LittleEndian.Uint64(body[48:56]))}
				copy(f.hash16k[:], body[32:48])
				f.name = path.Base(strings.ReplaceAll(strings.TrimRight(string(body[56:]), "\x00"), "\\", "/"))
				out = append(out, f)
			}
		}
		off += int(length)
	}
	return out
}

// par2Worth reports whether a failed selection may be an obfuscation problem
// that PAR2 names can fix: some names carry no usable volume or file name.
func par2Worth(ctx context.Context, err error, files []*File) bool {
	if err == nil || ctx.Err() != nil || errors.Is(err, errDamagedNZBFile) || errors.Is(err, nntppool.ErrArticleNotFound) ||
		errors.Is(err, errProviderAuthentication) || errors.Is(err, errProviderQuota) ||
		errors.Is(err, ErrCompressedRAR) || errors.Is(err, ErrCompressed7z) ||
		errors.Is(err, ErrRARPasswordMissing) || errors.Is(err, ErrRARWrongPassword) ||
		errors.Is(err, Err7zPasswordMissing) || errors.Is(err, Err7zWrongPassword) {
		return false
	}
	for _, f := range files {
		l := strings.ToLower(f.Name)
		if scheme, _, ok := rarname.VolumeNumber(l); ok && scheme == rarname.SchemeNumeric && !strings.Contains(l, ".7z.") {
			return true // hash.N numbering: names that need not follow volume order.
		}
		if _, ok := rarname.SetKey(l); ok || isVideo(l) || isSubtitle(l) || strings.HasSuffix(l, ".7z") || strings.HasSuffix(l, ".par2") {
			continue
		}
		if path.Ext(l) == "" || len(path.Ext(l)) > 6 {
			return true // a bare or random-looking extension.
		}
	}
	return false
}

// recoverPAR2Names renames files after the release's PAR2 index and reports
// how many changed.
func recoverPAR2Names(ctx context.Context, files []*File) (int, error) {
	var named, candidates []*File
	for _, f := range files {
		if f.damaged != nil {
			continue
		}
		if strings.HasSuffix(strings.ToLower(f.Name), ".par2") {
			named = append(named, f)
		} else {
			candidates = append(candidates, f)
		}
	}
	if len(candidates) == 0 || len(candidates) > par2MaxCandidates {
		return 0, nil
	}
	// The first article of every candidate gives its exact size, its first
	// 16 KiB and, for obfuscated PAR2 files, their signature.
	heads := make([][]byte, len(candidates))
	errs := make([]error, len(candidates))
	var wg sync.WaitGroup
	limit := make(chan struct{}, par2ReadConcurrent)
	for i, f := range candidates {
		wg.Add(1)
		limit <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-limit }()
			heads[i], errs[i] = par2Head(ctx, f)
		}()
	}
	wg.Wait()
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	indexes := append([]*File(nil), named...)
	for i, f := range candidates {
		if errs[i] == nil && bytes.HasPrefix(heads[i], par2Magic) {
			indexes = append(indexes, f)
		}
	}
	// The index file is the smallest PAR2 file; recovery volumes repeat the
	// descriptions, so try a couple in size order.
	sort.SliceStable(indexes, func(i, j int) bool { return indexes[i].Size() < indexes[j].Size() })
	var descs []par2File
	for _, f := range indexes[:min(len(indexes), 3)] {
		if descs = par2Descriptions(ctx, f); len(descs) > 0 {
			break
		}
	}
	if len(descs) == 0 {
		return 0, ctx.Err()
	}
	byHash := map[[md5.Size]byte][]par2File{}
	for _, d := range descs {
		byHash[d.hash16k] = append(byHash[d.hash16k], d)
	}
	renamed := 0
	for i, f := range candidates {
		if errs[i] != nil || bytes.HasPrefix(heads[i], par2Magic) {
			continue
		}
		size, exact := exactSize(f)
		matches := byHash[md5.Sum(heads[i])]
		if !exact || len(matches) != 1 || matches[0].size != size || matches[0].name == "" || matches[0].name == "." {
			continue
		}
		if f.Name != matches[0].name {
			f.Name = matches[0].name
			renamed++
		}
	}
	return renamed, nil
}

// par2Head reads the first 16 KiB of f (all of it when shorter).
func par2Head(ctx context.Context, f *File) ([]byte, error) {
	r := f.headerReader(ctx)
	defer r.Close()
	var one [1]byte
	if _, err := r.ReadAt(one[:], 0); err != nil && err != io.EOF {
		return nil, err
	}
	b := make([]byte, min(int64(par2Hash16k), f.Size()))
	if err := readFullAt(r, b, 0); err != nil {
		return nil, err
	}
	return b, nil
}

func par2Descriptions(ctx context.Context, f *File) []par2File {
	r := f.headerReader(ctx)
	defer r.Close()
	var one [1]byte
	if _, err := r.ReadAt(one[:], 0); err != nil && err != io.EOF {
		return nil // The first article also establishes the exact size.
	}
	size := f.Size()
	if size <= 0 || size > par2IndexMaxBytes {
		return nil
	}
	b := make([]byte, size)
	if err := readFullAt(r, b, 0); err != nil {
		return nil
	}
	return parsePAR2(b)
}
