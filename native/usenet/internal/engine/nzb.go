package engine

import (
	"bufio"
	"compress/gzip"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/javi11/nntppool/v4"
	"github.com/javi11/nzbparser"
	"golang.org/x/net/html/charset"
)

type segment struct {
	id         string
	wire       int64
	begin, end int64
	known      bool
	hint       bool // Restored metadata; fresh yEnc always takes precedence.
}

type File struct {
	loadOnce      sync.Once
	loadErr       error
	cached        *cachedNZBFile
	hintSize      bool
	learnedAt     int64
	mu            sync.RWMutex
	Name          string
	Index         int
	order         int    // subject's release order, when provided; Index stays the NZB order.
	recoveredName string // yEnc name discovered during normal streaming.
	segments      []segment
	prefix        []int64
	size          int64
	exact         bool
	known         []int // sorted authoritative anchors; Range lookup is O(log segments).
	store         *Store
}

const maxNZBBytes = 64 << 20

var errNZBTooLarge = errors.New("NZB exceeds 64 MiB size limit")

// Unlike LimitReader, crossing the bound returns an explicit error instead of
// EOF. Probe one byte at the boundary so an exactly full document still works.
type nzbLimitReader struct {
	r         io.Reader
	remaining int64
}

func (r *nzbLimitReader) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if r.remaining == 0 {
		var probe [1]byte
		n, err := r.r.Read(probe[:])
		if n > 0 {
			return 0, errNZBTooLarge
		}
		return 0, err
	}
	p = p[:min(int64(len(p)), r.remaining)]
	n, err := r.r.Read(p)
	r.remaining -= int64(n)
	return n, err
}

func ParseNZB(r io.Reader, store *Store) ([]*File, error) {
	// Only NZB XML is buffered/decompressed here. Article/video payloads never
	// pass through bufio or a line scanner; nntppool uses chunked rapidyenc.
	br := bufio.NewReader(&nzbLimitReader{r: r, remaining: maxNZBBytes})
	magic, _ := br.Peek(2)
	var input io.Reader = br
	if len(magic) == 2 && magic[0] == 0x1f && magic[1] == 0x8b {
		gz, err := gzip.NewReader(br)
		if err != nil {
			return nil, err
		}
		defer gz.Close()
		input = gz
	}
	// Adapt nzbparser's XML/subject handling, decoding one file at a time. Its
	// full-document parser sorts files and retains a second complete XML model,
	// which changes addon fileIdx semantics and wastes season-pack RAM.
	decoder := xml.NewDecoder(&nzbLimitReader{r: input, remaining: maxNZBBytes})
	decoder.CharsetReader = charset.NewReaderLabel
	var files []*File
	count := 0
	root := false
	index := 0
	for {
		token, err := decoder.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("invalid NZB: %w", err)
		}
		start, ok := token.(xml.StartElement)
		if !ok {
			continue
		}
		if !root {
			if start.Name.Local != "nzb" {
				return nil, errors.New("invalid NZB root")
			}
			root = true
			continue
		}
		if start.Name.Local != "file" {
			if err := decoder.Skip(); err != nil {
				return nil, err
			}
			continue
		}
		if index >= 10000 {
			return nil, errors.New("NZB exceeds file limit")
		}
		var nf struct {
			Subject  string `xml:"subject,attr"`
			Segments []struct {
				Number int    `xml:"number,attr"`
				Bytes  int64  `xml:"bytes,attr"`
				ID     string `xml:",chardata"`
			} `xml:"segments>segment"`
		}
		if err := decoder.DecodeElement(&nf, &start); err != nil {
			return nil, fmt.Errorf("invalid NZB file: %w", err)
		}
		i := index
		index++
		if len(nf.Segments) == 0 {
			continue
		}
		count += len(nf.Segments)
		if count > 500000 {
			return nil, errors.New("NZB exceeds segment metadata limit")
		}
		sort.Slice(nf.Segments, func(i, j int) bool { return nf.Segments[i].Number < nf.Segments[j].Number })
		subject, err := nzbparser.ParseSubject(nf.Subject)
		if err != nil {
			return nil, errors.New("invalid NZB subject")
		}
		f := &File{Name: strings.Trim(subject.Filename, "\" '"), Index: i, store: store, prefix: make([]int64, len(nf.Segments)+1), segments: make([]segment, 0, len(nf.Segments))}
		if subject.TotalFiles > 1 {
			f.order = subject.File
		}
		for j, s := range nf.Segments {
			id := strings.Trim(strings.TrimSpace(s.ID), "<>")
			if s.Number != j+1 || id == "" || len(id) > 998 || strings.ContainsAny(id, "\r\n\x00<>") || s.Bytes <= 0 {
				return nil, errors.New("NZB contains missing/duplicate segments or invalid article metadata")
			}
			f.segments = append(f.segments, segment{id: id, wire: int64(s.Bytes)})
			if s.Bytes > (1<<63-1)-f.prefix[j] {
				return nil, errors.New("NZB size overflow")
			}
			f.prefix[j+1] = f.prefix[j] + int64(s.Bytes)
		}
		f.size = f.prefix[len(f.segments)]
		files = append(files, f)
	}
	if len(files) == 0 {
		return nil, errors.New("NZB has no files")
	}
	return files, nil
}

func FetchNZB(ctx context.Context, client *http.Client, raw string, headers map[string]string, store *Store, fastNZBFetch bool) ([]*File, error) {
	return fetchNZB(ctx, client, raw, headers, store, fastNZBFetch, nil, "", nil)
}

func fetchNZB(ctx context.Context, client *http.Client, raw string, headers map[string]string, store *Store, fastNZBFetch bool, cache *nzbCache, scope string, trace *startupTrace) ([]*File, error) {
	diagnostic := nzbCacheDiagnostic{Lookup: "disabled"}
	defer func() { trace.recordNZBCache(diagnostic) }()
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, errors.New("NZB URL must use HTTP or HTTPS")
	}
	// Addon URLs may be signed, including their exact query encoding/order.
	// Negotiate compression through HTTP without modifying the supplied URL.
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return nil, errors.New("invalid NZB URL")
	}
	if fastNZBFetch {
		req.Header.Set("Accept-Encoding", "gzip")
	}
	for k, v := range headers {
		if !strings.EqualFold(k, "Range") && !strings.EqualFold(k, "Host") {
			req.Header.Set(k, v)
		}
	}
	key := ""
	if cache != nil && store != nil {
		defer func() {
			store.mu.Lock()
			doc := store.nzb
			store.mu.Unlock()
			if doc != nil {
				doc.recover = func() ([]*File, error) { return fetchNZB(ctx, client, raw, headers, store, fastNZBFetch, nil, "", nil) }
			}
		}()
	}
	if cache != nil {
		key = nzbCacheKey(req, scope)
		files, reason, size := cache.read(key, store)
		if reason == "hit" {
			diagnostic.Format = "legacy"
			if len(files) > 0 && files[0].cached != nil {
				diagnostic.Format = "indexed"
			}
			diagnostic.Lookup = "hit"
			diagnostic.Bytes = size
			return files, nil
		}
		diagnostic.Lookup = "miss"
		diagnostic.Reason = reason
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, errors.New("could not download NZB")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("NZB download returned HTTP %d", resp.StatusCode)
	}
	fill, outcome := cache.begin(key)
	if fill != nil {
		fill.ctx = ctx
		files, err := ParseNZB(resp.Body, store)
		if err == nil && ctx.Err() == nil {
			diagnostic.Write = fill.indexed(files)
			if diagnostic.Write == "saved" {
				diagnostic.Format = "indexed"
				files = cache.bindSaved(key, files, store)
			}
		} else {
			diagnostic.Write = fill.finish(false)
		}
		diagnostic.Bytes = fill.written
		if ctx.Err() != nil {
			diagnostic.Write = "cancelled"
		} else if errors.Is(err, errNZBTooLarge) {
			diagnostic.Write = "oversized"
		}
		return files, err
	}
	if cache != nil {
		diagnostic.Write = outcome
	}
	return ParseNZB(resp.Body, store)
}

func (f *File) Size() int64 { f.mu.RLock(); defer f.mu.RUnlock(); return f.size }

func (f *File) learn(i int, m nntppool.YEncMeta) error {
	begin := m.PartBegin
	size := m.PartSize // nntppool has already converted to zero-based.
	if m.Part == 0 {
		begin = 0
		size = m.FileSize
	}
	if begin < 0 || size <= 0 || begin > m.FileSize-size || (m.Part > 0 && m.Part != int64(i+1)) || (m.Total > 0 && m.Total != int64(len(f.segments))) {
		return errors.New("invalid yEnc segment layout")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if (f.hintSize && f.size != m.FileSize) ||
		(f.segments[i].hint && (f.segments[i].begin != begin || f.segments[i].end != begin+size)) ||
		(i > 0 && f.segments[i-1].hint && f.segments[i-1].end != begin) ||
		(i+1 < len(f.segments) && f.segments[i+1].hint && f.segments[i+1].begin != begin+size) {
		known := f.known[:0]
		for _, j := range f.known {
			if f.segments[j].hint {
				f.segments[j].known, f.segments[j].hint = false, false
			} else {
				known = append(known, j)
			}
		}
		f.known = known
		if f.hintSize {
			f.exact = false
		}
		f.hintSize = false
	}
	if m.FileName != "" {
		f.recoveredName = m.FileName
	}
	if f.exact && f.size != m.FileSize {
		return errors.New("conflicting yEnc file sizes")
	}
	s := &f.segments[i]
	if s.known && (s.begin != begin || s.end != begin+size) {
		return errors.New("conflicting yEnc segment offsets")
	}
	if (i == 0 && begin != 0) || (i == len(f.segments)-1 && begin+size != m.FileSize) {
		return errors.New("incomplete yEnc file")
	}
	if i > 0 && f.segments[i-1].known && f.segments[i-1].end != begin {
		return errors.New("gap/overlap in yEnc layout")
	}
	if i+1 < len(f.segments) && f.segments[i+1].known && f.segments[i+1].begin != begin+size {
		return errors.New("gap/overlap in yEnc layout")
	}
	if !s.known {
		j := sort.SearchInts(f.known, i)
		f.known = append(f.known, 0)
		copy(f.known[j+1:], f.known[j:])
		f.known[j] = i
	}
	s.begin = begin
	s.end = begin + size
	s.known = true
	s.hint = false
	f.size = m.FileSize
	f.exact = true
	f.hintSize = false
	f.learnedAt = time.Now().UnixNano()
	return nil
}

// locate uses NZB wire-size weights between authoritative yEnc anchors. An
// estimate is NEVER exposed as a payload offset. If it lands on a neighbouring
// article, the same streaming fetch corrects the search interval and is cached.
func (f *File) locate(off int64, lo, hi int) int {
	f.mu.RLock()
	defer f.mu.RUnlock()
	left, right := int64(0), f.size
	j := sort.Search(len(f.known), func(j int) bool { return f.segments[f.known[j]].end > off })
	if j < len(f.known) {
		i := f.known[j]
		s := f.segments[i]
		if off >= s.begin {
			return i
		}
		hi = min(hi, i-1)
		right = s.begin
	}
	if j > 0 {
		i := f.known[j-1]
		lo = max(lo, i+1)
		left = f.segments[i].end
	}
	if lo > hi {
		return -1
	}
	if lo > 0 && f.segments[lo-1].known {
		left = f.segments[lo-1].end
	}
	if hi+1 < len(f.segments) && f.segments[hi+1].known {
		right = f.segments[hi+1].begin
	}
	if right <= left {
		return lo
	}
	target := f.prefix[lo] + int64(float64(off-left)/float64(right-left)*float64(f.prefix[hi+1]-f.prefix[lo]))
	i := sort.Search(hi-lo+1, func(j int) bool { return f.prefix[lo+j+1] > target }) + lo
	return min(i, hi)
}

type FileReader struct {
	f      *File
	ctx    context.Context
	ahead  int
	leases map[int]*article
	pos    int64
	header bool
}

func (f *File) Reader(ctx context.Context, ahead int) *FileReader {
	return &FileReader{f: f, ctx: ctx, ahead: ahead, leases: make(map[int]*article)}
}

func (f *File) headerReader(ctx context.Context) *FileReader {
	r := f.Reader(ctx, 0)
	r.header = true
	return r
}

func (r *FileReader) release(a *article) {
	if r.header {
		r.f.store.releaseHeader(a)
	} else {
		r.f.store.release(a)
	}
}

// Bound each window by count AND estimated bytes. Two windows at a RAR
// boundary fit in the store's speculative allowance; the store still enforces
// its hard allocation limit if NZB byte estimates understate decoded sizes.
func (r *FileReader) windowEnd(i int) int {
	r.f.mu.RLock()
	defer r.f.mu.RUnlock()
	budget := r.f.store.readAheadLimit()
	end := i
	for j := i + 1; j < len(r.f.segments) && j <= i+r.ahead; j++ {
		s := r.f.segments[j]
		size := s.wire
		if s.known {
			size = s.end - s.begin
		}
		// NZB counts include encoding overhead. Capping at the largest
		// supported article prevents pathological estimates overflowing.
		bytes := min(max(size, 64<<10), int64(maxArticleBytes))
		if bytes > budget {
			break
		}
		budget -= bytes
		end = j
	}
	return end
}

func (r *FileReader) trim(i int) {
	end := r.windowEnd(i)
	for j, a := range r.leases {
		if j < i || j > end {
			r.release(a)
			delete(r.leases, j)
		}
	}
}

func (r *FileReader) get(i int, spec bool) *article {
	if a := r.leases[i]; a != nil {
		if spec {
			return a
		}
		a.mu.Lock()
		failed := a.err != nil
		a.mu.Unlock()
		if !failed {
			r.f.store.mu.Lock()
			a.speculative = false
			r.f.store.mu.Unlock()
			return a
		}
		r.release(a)
		delete(r.leases, i)
	}
	a := r.f.store.acquire(r.f.segments[i].id, spec)
	if a != nil {
		r.leases[i] = a
	}
	return a
}

func (r *FileReader) ReadAt(p []byte, off int64) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if off < 0 {
		return 0, errors.New("negative file offset")
	}
	if err := r.f.loadSegments(); err != nil {
		return 0, err
	}
	if off >= r.f.Size() {
		return 0, io.EOF
	}
	lo, hi := 0, len(r.f.segments)-1
	for lo <= hi {
		i := r.f.locate(off, lo, hi)
		if i < 0 {
			return 0, errors.New("uncovered file offset")
		}
		r.trim(i)
		a := r.get(i, false)
		if a == nil {
			return 0, context.Canceled
		}
		m, err := a.metadata(r.ctx)
		if err != nil {
			return 0, err
		}
		if err = r.f.learn(i, m); err != nil {
			return 0, err
		}
		r.f.mu.RLock()
		seg := r.f.segments[i]
		r.f.mu.RUnlock()
		if off < seg.begin {
			hi = i - 1
			continue
		}
		if off >= seg.end {
			lo = i + 1
			continue
		}
		// Only the active Range's window is prefetched. Closing/cancelling it
		// releases leases without disrupting overlapping live Range readers.
		end := r.windowEnd(i)
		for j := i + 1; j <= end; j++ {
			r.get(j, true)
		}
		return a.readAt(r.ctx, p[:min(int64(len(p)), seg.end-off)], off-seg.begin)
	}
	return 0, errors.New("NZB layout does not cover requested offset")
}

func (r *FileReader) Read(p []byte) (int, error) {
	n, e := r.ReadAt(p, r.pos)
	r.pos += int64(n)
	return n, e
}
func (r *FileReader) Seek(off int64, whence int) (int64, error) {
	switch whence {
	case io.SeekStart:
	case io.SeekCurrent:
		off += r.pos
	case io.SeekEnd:
		off += r.f.Size()
	default:
		return r.pos, errors.New("invalid whence")
	}
	if off < 0 {
		return r.pos, errors.New("negative seek")
	}
	if off != r.pos && len(r.leases) > 0 {
		if off >= r.f.Size() {
			r.Close()
		} else if i := r.f.locate(off, 0, len(r.f.segments)-1); i >= 0 {
			r.trim(i)
		}
	}
	r.pos = off
	return off, nil
}
func (r *FileReader) Close() error {
	for i, a := range r.leases {
		r.release(a)
		delete(r.leases, i)
	}
	return nil
}

func readFullAt(r interface {
	ReadAt([]byte, int64) (int, error)
}, p []byte, off int64) error {
	for len(p) > 0 {
		n, e := r.ReadAt(p, off)
		p = p[n:]
		off += int64(n)
		if len(p) == 0 {
			return nil
		}
		if e != nil {
			return e
		}
		if n == 0 {
			return io.ErrNoProgress
		}
	}
	return nil
}
