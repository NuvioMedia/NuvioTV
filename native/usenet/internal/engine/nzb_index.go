package engine

// The cache is a normalized NZB, not an XML archive: it retains every file's
// original index, subject filename/order, message IDs and wire sizes. Unused XML
// fields (groups, poster, comments) are deliberately not duplicated on disk.
// A checksummed plain directory addresses independent gzip/binary file records.
// Selection reads only the directory; readers inflate only files they touch.

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"sync"
	"sync/atomic"
)

const (
	nzbIndexMagic     = "NVIDX001"
	nzbIndexHeader    = 56
	nzbDirectoryBytes = 4 << 20
)

var errNZBIndex = errors.New("invalid cached NZB index")

type nzbRecord struct {
	Name   string   `json:"n"`
	Index  int      `json:"i"`
	Order  int      `json:"o,omitempty"`
	Size   int64    `json:"s"`
	Count  int      `json:"c"`
	Offset int64    `json:"p"`
	Length int64    `json:"l"`
	Bytes  int64    `json:"b"`
	Hash   [32]byte `json:"h"`
}

type nzbSnapshot struct {
	file        *os.File
	cache       *nzbCache
	key         string
	generation  [32]byte
	records     []nzbRecord
	closeOnce   sync.Once
	closed      atomic.Bool
	recoverOnce sync.Once
	recover     func() ([]*File, error)
	recovered   []*File
	recoverErr  error
	hintMu      sync.Mutex
	hints       nzbHints
	bound       []*File
}

type cachedNZBFile struct {
	doc    *nzbSnapshot
	record nzbRecord
}

func (d *nzbSnapshot) Close() {
	d.closeOnce.Do(func() {
		d.closed.Store(true)
		d.flushHints()
		d.file.Close()
		d.cache.mu.Lock()
		d.cache.active[d.key]--
		if d.cache.active[d.key] == 0 {
			delete(d.cache.active, d.key)
			if d.cache.invalid[d.key] {
				d.cache.remove(d.key)
			}
		}
		d.cache.mu.Unlock()
	})
}

func (f *File) loadSegments() error {
	f.loadOnce.Do(func() {
		if f.cached == nil {
			return
		}
		source := f.cached
		loaded, err := source.doc.load(source.record)
		if err != nil {
			loaded, err = source.doc.recoverFile(source.record)
		}
		if err != nil {
			f.loadErr = err
			return
		}
		f.mu.Lock()
		f.segments, f.prefix = loaded.segments, loaded.prefix
		f.mu.Unlock()
		source.restoreHints(f)
	})
	return f.loadErr
}

// On late record corruption/eviction, one session-local recovery downloads and
// parses the source. A digest match is required before mixing recovered records
// with already loaded records. Changed indexer responses require a fresh open.
func (d *nzbSnapshot) recoverFile(r nzbRecord) (*File, error) {
	if d.closed.Load() {
		return nil, context.Canceled
	}
	d.cache.mu.Lock()
	d.cache.invalid[d.key] = true
	d.cache.mu.Unlock()
	d.recoverOnce.Do(func() {
		if d.recover == nil {
			d.recoverErr = errNZBIndex
			return
		}
		d.recovered, d.recoverErr = d.recover()
	})
	if d.recoverErr != nil {
		return nil, d.recoverErr
	}
	for _, f := range d.recovered {
		if f.Index == r.Index && f.Name == r.Name && f.order == r.Order && f.Size() == r.Size && len(f.segments) == r.Count {
			h := sha256.New()
			writeNZBSegments(h, f.segments)
			if bytes.Equal(h.Sum(nil), r.Hash[:]) {
				// Each File owns its mutable layout even on recovery.
				return &File{segments: append([]segment(nil), f.segments...), prefix: append([]int64(nil), f.prefix...)}, nil
			}
		}
	}
	return nil, errors.New("cached NZB changed; reopen the stream")
}

func writeNZBSegments(w io.Writer, segments []segment) error {
	var num [binary.MaxVarintLen64]byte
	for _, s := range segments {
		n := binary.PutUvarint(num[:], uint64(s.wire))
		if _, err := w.Write(num[:n]); err != nil {
			return err
		}
		n = binary.PutUvarint(num[:], uint64(len(s.id)))
		if _, err := w.Write(num[:n]); err != nil {
			return err
		}
		if _, err := io.WriteString(w, s.id); err != nil {
			return err
		}
	}
	return nil
}

func (d *nzbSnapshot) load(r nzbRecord) (*File, error) {
	gz, err := gzip.NewReader(io.NewSectionReader(d.file, r.Offset, r.Length))
	if err != nil {
		return nil, errNZBIndex
	}
	defer gz.Close()
	h := sha256.New()
	br := bufio.NewReader(io.TeeReader(&nzbLimitReader{r: gz, remaining: r.Bytes}, h))
	f := &File{segments: make([]segment, r.Count), prefix: make([]int64, r.Count+1)}
	var id [998]byte
	for i := range f.segments {
		wire, err := binary.ReadUvarint(br)
		if err != nil || wire == 0 || wire > uint64(r.Size-f.prefix[i]) {
			return nil, errNZBIndex
		}
		n, err := binary.ReadUvarint(br)
		if err != nil || n == 0 || n > uint64(len(id)) {
			return nil, errNZBIndex
		}
		if _, err := io.ReadFull(br, id[:n]); err != nil {
			return nil, errNZBIndex
		}
		s := string(id[:n])
		if strings.ContainsAny(s, "\r\n\x00<>") {
			return nil, errNZBIndex
		}
		f.segments[i] = segment{id: s, wire: int64(wire)}
		f.prefix[i+1] = f.prefix[i] + int64(wire)
	}
	if _, err := br.ReadByte(); err != io.EOF || f.prefix[r.Count] != r.Size || !bytes.Equal(h.Sum(nil), r.Hash[:]) {
		return nil, errNZBIndex
	}
	return f, nil
}

// Called while the cache directory lock is held. Payload records are not read.
func (c *nzbCache) readIndex(f *os.File, key string, size int64) (*nzbSnapshot, error) {
	var header [nzbIndexHeader]byte
	if _, err := f.ReadAt(header[:], 0); err != nil || string(header[:8]) != nzbIndexMagic {
		return nil, errNZBIndex
	}
	offset := binary.LittleEndian.Uint64(header[8:16])
	length := binary.LittleEndian.Uint64(header[16:24])
	if offset < nzbIndexHeader || offset > uint64(size) || length == 0 || length > nzbDirectoryBytes || length != uint64(size)-offset {
		return nil, errNZBIndex
	}
	b := make([]byte, int(length))
	if _, err := f.ReadAt(b, int64(offset)); err != nil {
		return nil, errNZBIndex
	}
	hash := sha256.Sum256(b)
	if !bytes.Equal(hash[:], header[24:]) {
		return nil, errNZBIndex
	}
	records, err := decodeNZBDirectory(b)
	if err != nil {
		return nil, errNZBIndex
	}
	count, lastIndex := 0, -1
	end, decoded := int64(nzbIndexHeader), int64(0)
	for _, r := range records {
		if r.Index <= lastIndex || r.Index >= 10000 || r.Order < 0 || r.Size <= 0 || r.Count <= 0 || r.Count > 500000-count ||
			r.Offset != end || r.Length < 18 || r.Length > int64(offset)-end || r.Bytes < int64(r.Count)*3 || r.Bytes > maxNZBBytes-decoded {
			return nil, errNZBIndex
		}
		lastIndex, end, count, decoded = r.Index, end+r.Length, count+r.Count, decoded+r.Bytes
	}
	if end != int64(offset) {
		return nil, errNZBIndex
	}
	c.active[key]++
	return &nzbSnapshot{file: f, cache: c, key: key, generation: hash, records: records}, nil
}

// Enforce the file count while decoding, before an edited/corrupt directory
// can allocate an arbitrarily large slice of records on a low-memory device.
func decodeNZBDirectory(b []byte) ([]nzbRecord, error) {
	decoder := json.NewDecoder(bytes.NewReader(b))
	if token, err := decoder.Token(); err != nil || token != json.Delim('[') {
		return nil, errNZBIndex
	}
	var records []nzbRecord
	for decoder.More() {
		if len(records) >= 10000 {
			return nil, errNZBIndex
		}
		var r nzbRecord
		if decoder.Decode(&r) != nil {
			return nil, errNZBIndex
		}
		records = append(records, r)
	}
	if token, err := decoder.Token(); err != nil || token != json.Delim(']') || len(records) == 0 {
		return nil, errNZBIndex
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, errNZBIndex
	}
	return records, nil
}

func (d *nzbSnapshot) files(store *Store) []*File {
	files := make([]*File, len(d.records))
	for i, r := range d.records {
		files[i] = &File{Name: r.Name, Index: r.Index, order: r.Order, size: r.Size, store: store, cached: &cachedNZBFile{doc: d, record: r}}
	}
	return files
}

type byteCounter struct{ n int64 }

func (c *byteCounter) Write(p []byte) (int, error) { c.n += int64(len(p)); return len(p), nil }

// One compressor and buffer are reused across records, keeping season-pack
// writes bounded. Only validated parser output enters this format.
func (f *nzbCacheFill) indexed(files []*File) string {
	f.Write(make([]byte, nzbIndexHeader))
	var records []nzbRecord
	gz, _ := gzip.NewWriterLevel(f, gzip.BestSpeed)
	bw := bufio.NewWriterSize(io.Discard, 32<<10)
	for _, file := range files {
		if f.ctx != nil && f.ctx.Err() != nil {
			return f.finish(false)
		}
		if err := file.loadSegments(); err != nil {
			return f.finish(false)
		}
		r := nzbRecord{Name: file.Name, Index: file.Index, Order: file.order, Size: file.prefix[len(file.prefix)-1], Count: len(file.segments), Offset: f.written}
		h := sha256.New()
		count := &byteCounter{}
		gz.Reset(f)
		bw.Reset(io.MultiWriter(gz, h, count))
		if writeNZBSegments(bw, file.segments) != nil || bw.Flush() != nil || gz.Close() != nil {
			return f.finish(false)
		}
		r.Length, r.Bytes = f.written-r.Offset, count.n
		copy(r.Hash[:], h.Sum(nil))
		records = append(records, r)
		if f.failure != "" {
			return f.finish(false)
		}
	}
	b, err := json.Marshal(records)
	if err != nil || len(b) > nzbDirectoryBytes {
		return f.finish(false)
	}
	var header [nzbIndexHeader]byte
	copy(header[:], nzbIndexMagic)
	binary.LittleEndian.PutUint64(header[8:16], uint64(f.written))
	binary.LittleEndian.PutUint64(header[16:24], uint64(len(b)))
	hash := sha256.Sum256(b)
	copy(header[24:], hash[:])
	f.Write(b)
	if _, err := f.file.WriteAt(header[:], 0); err != nil {
		f.failure = "disk_error"
	}
	return f.finish(len(files) > 0)
}
