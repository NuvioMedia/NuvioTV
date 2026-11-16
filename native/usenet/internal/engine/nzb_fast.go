package engine

import (
	"bytes"
	"fmt"
	"io"
	"strconv"
	"strings"
	"unicode/utf8"
)

// encoding/xml reads an NZB a byte at a time through interfaces and
// reflection: a 16 MB remux NZB took 7.7 s of CPU on a 32-bit Android box,
// three quarters of opening the release. readNZBFast reads the shape almost
// every NZB has directly from its bytes:
//
//	<?xml version="1.0" encoding="UTF-8"?> (optional, UTF-8 only)
//	<!DOCTYPE ...> (optional, without an internal subset)
//	<nzb ...>
//	  <head><meta type="...">value</meta>...</head>
//	  <file subject="..." ...>
//	    <groups><group>name</group>...</groups>
//	    <segments><segment bytes="..." number="...">message-id</segment>...</segments>
//	  </file>...
//	</nzb>
//
// Comments may come before the root and between its children, as newznab's
// closing one does. It resolves references, checks characters and converts
// numbers as encoding/xml does, and hands the same values to the same code.
// At anything else, from CDATA and namespace prefixes to other encodings, line
// ends to rewrite, repeated attributes or a malformed element, it stops before
// that element and the decoder reads the rest of the document as it always
// has, so it reports the same errors.

// fastNZBReader is off in tests that compare the fast reader with encoding/xml alone.
var fastNZBReader = true

type nzbMeta struct {
	Type  string `xml:"type,attr"`
	Value string `xml:",chardata"`
}

type nzbFastHooks struct {
	head      func([]nzbMeta)
	fileLimit func() error
	segments  func() boundedNZBSegments
	file      func(subject string, segments []nzbSegment) error
}

const nzbScanChunk = 256 << 10

// The decoder reads an element from its start when the fast reader stops
// inside it, so the element's bytes stay buffered: a larger one goes to the
// decoder (a single file of about 230 GB). Tests lower it.
var nzbFastMaxElement = 32 << 20

// nzbShape stops the fast reader before the current element.
type nzbShape struct{}

// nzbStop ends the parse with an error the decoder would also report there.
type nzbStop struct{ err error }

type nzbScanner struct {
	r    io.Reader
	buf  []byte
	mark int   // start of the element being read; kept for the decoder
	err  error // the reader's final error, io.EOF included, replayed to the decoder
}

func (s *nzbScanner) more() bool {
	if s.err != nil || len(s.buf)-s.mark >= nzbFastMaxElement {
		return false
	}
	if len(s.buf) == cap(s.buf) {
		grown := make([]byte, len(s.buf), max(2*cap(s.buf), len(s.buf)+nzbScanChunk))
		copy(grown, s.buf)
		s.buf = grown
	}
	for empty := 0; ; empty++ {
		n, err := s.r.Read(s.buf[len(s.buf):cap(s.buf)])
		s.buf = s.buf[:len(s.buf)+n]
		if err != nil {
			s.err = err
			return n > 0
		}
		if n > 0 {
			return true
		}
		if empty == 100 {
			s.err = io.ErrNoProgress
			return false
		}
	}
}

func (s *nzbScanner) at(i int) byte {
	for i >= len(s.buf) {
		if !s.more() {
			panic(nzbShape{})
		}
	}
	return s.buf[i]
}

// find returns the index of the next c at or after i.
func (s *nzbScanner) find(i int, c byte) int {
	for {
		if j := bytes.IndexByte(s.buf[i:], c); j >= 0 {
			return i + j
		}
		i = len(s.buf)
		if !s.more() {
			panic(nzbShape{})
		}
	}
}

func isXMLSpace(b byte) bool { return b == ' ' || b == '\t' || b == '\n' || b == '\r' }

func (s *nzbScanner) space(i int) int {
	for isXMLSpace(s.at(i)) {
		i++
	}
	return i
}

func (s *nzbScanner) lit(i int, lit string) bool {
	for k := 0; k < len(lit); k++ {
		if s.at(i+k) != lit[k] {
			return false
		}
	}
	return true
}

// open reports whether a start tag of name begins at i.
func (s *nzbScanner) open(i int, name string) bool {
	if !s.lit(i, "<") || !s.lit(i+1, name) {
		return false
	}
	b := s.at(i + 1 + len(name))
	return isXMLSpace(b) || b == '>' || b == '/'
}

// isClose reports whether an end tag of name begins at i.
func (s *nzbScanner) isClose(i int, name string) bool {
	if !s.lit(i, "</") || !s.lit(i+2, name) {
		return false
	}
	b := s.at(i + 2 + len(name))
	return isXMLSpace(b) || b == '>'
}

func (s *nzbScanner) close(i int, name string) int {
	if !s.isClose(i, name) {
		panic(nzbShape{})
	}
	i = s.space(i + 2 + len(name))
	if s.at(i) != '>' {
		panic(nzbShape{})
	}
	return i + 1
}

// name returns the end of an ASCII name without a namespace prefix.
func (s *nzbScanner) name(i int) int {
	if b := s.at(i); !(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b == '_') {
		panic(nzbShape{})
	}
	for {
		i++
		b := s.at(i)
		if !(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '_' || b == '-' || b == '.') {
			return i
		}
	}
}

// attrs reads the attributes after a start tag's name at i, checking every
// value; set receives the ones listed in names. It returns the index after
// the tag and whether the element closes itself.
func (s *nzbScanner) attrs(i int, names []string, set func(k int, value []byte)) (int, bool) {
	var seen uint8
	for {
		i = s.space(i)
		switch s.at(i) {
		case '>':
			return i + 1, false
		case '/':
			if s.at(i+1) != '>' {
				panic(nzbShape{})
			}
			return i + 2, true
		}
		end := s.name(i)
		name := s.buf[i:end]
		i = s.space(end)
		if s.at(i) != '=' {
			panic(nzbShape{})
		}
		i = s.space(i + 1)
		quote := s.at(i)
		if quote != '"' && quote != '\'' {
			panic(nzbShape{})
		}
		close := s.find(i+1, quote)
		value := nzbText(s.buf[i+1:close], true)
		for k, want := range names {
			if string(name) == want {
				if seen&(1<<k) != 0 {
					panic(nzbShape{}) // encoding/xml applies each copy in turn
				}
				seen |= 1 << k
				set(k, value)
			}
		}
		i = close + 1
	}
}

// text reads character data at i up to the next tag.
func (s *nzbScanner) text(i int) ([]byte, int) {
	end := s.find(i, '<')
	return nzbText(s.buf[i:end], false), end
}

var nzbSpecial = func() (t [256]bool) {
	for b := 0; b < 0x20; b++ {
		t[b] = true
	}
	for b := 0x80; b < 0x100; b++ {
		t[b] = true
	}
	t['&'], t['<'], t[']'] = true, true, true
	return
}()

// nzbText returns raw character data as encoding/xml decodes it, quoted for
// an attribute value.
func nzbText(raw []byte, quoted bool) []byte {
	for _, b := range raw {
		if nzbSpecial[b] {
			return nzbTextSlow(raw, quoted)
		}
	}
	return raw
}

func nzbTextSlow(raw []byte, quoted bool) []byte {
	out := make([]byte, 0, len(raw))
	for i := 0; i < len(raw); {
		switch b := raw[i]; {
		case b == '\t' || b == '\n':
			out = append(out, b)
			i++
		case b < 0x20 || b == '<':
			// A carriage return the decoder rewrites, a character it rejects,
			// or < in an attribute value, which it rejects too.
			panic(nzbShape{})
		case b == ']':
			if !quoted && i+2 < len(raw) && raw[i+1] == ']' && raw[i+2] == '>' {
				panic(nzbShape{})
			}
			out = append(out, b)
			i++
		case b == '&':
			semi := bytes.IndexByte(raw[i:min(len(raw), i+12)], ';')
			if semi < 0 {
				panic(nzbShape{})
			}
			r := nzbReference(raw[i+1 : i+semi])
			if r < 0 {
				panic(nzbShape{})
			}
			out = append(out, byte(r))
			i += semi + 1
		case b >= utf8.RuneSelf:
			r, size := utf8.DecodeRune(raw[i:])
			if r == utf8.RuneError && size == 1 || !xmlCharacter(r) {
				panic(nzbShape{})
			}
			out = append(out, raw[i:i+size]...)
			i += size
		default:
			out = append(out, b)
			i++
		}
	}
	return out
}

// xmlCharacter is the XML Char production, as encoding/xml checks it.
func xmlCharacter(r rune) bool {
	return r == 0x09 || r == 0x0A || r == 0x0D || r >= 0x20 && r <= 0xD7FF ||
		r >= 0xE000 && r <= 0xFFFD || r >= 0x10000 && r <= 0x10FFFF
}

// nzbReference resolves a predefined entity or a character reference that
// stands for printable ASCII, a tab or a newline; -1 for anything else.
func nzbReference(name []byte) int {
	switch string(name) {
	case "lt":
		return '<'
	case "gt":
		return '>'
	case "amp":
		return '&'
	case "apos":
		return '\''
	case "quot":
		return '"'
	}
	if len(name) < 2 || name[0] != '#' {
		return -1
	}
	digits, base := name[1:], 10
	if digits[0] == 'x' {
		digits, base = digits[1:], 16
	}
	if len(digits) == 0 {
		return -1
	}
	for _, d := range digits {
		if !(d >= '0' && d <= '9' || base == 16 && (d >= 'a' && d <= 'f' || d >= 'A' && d <= 'F')) {
			return -1
		}
	}
	n, err := strconv.ParseUint(string(digits), base, 64)
	if err != nil || !(n == '\t' || n == '\n' || n >= 0x20 && n <= 0x7e) {
		return -1
	}
	return int(n)
}

// nzbInt converts an attribute value as encoding/xml converts it into an int field.
func nzbInt(value []byte, bits int) int64 {
	if len(value) == 0 {
		return 0
	}
	n, err := strconv.ParseInt(strings.TrimSpace(string(value)), 10, bits)
	if err != nil {
		panic(nzbShape{}) // the decoder reports the conversion error
	}
	return n
}

// xmlProcInst is encoding/xml's procInst: the declaration's version and
// encoding are found exactly as the decoder finds them.
func xmlProcInst(param, s string) string {
	param = param + "="
	lenp := len(param)
	i := 0
	var sep byte
	for i < len(s) {
		sub := s[i:]
		k := strings.Index(sub, param)
		if k < 0 || lenp+k >= len(sub) {
			return ""
		}
		i += lenp + k + 1
		if c := sub[lenp+k]; c == '\'' || c == '"' {
			sep = c
			break
		}
	}
	if sep == 0 {
		return ""
	}
	j := strings.IndexByte(s[i:], sep)
	if j < 0 {
		return ""
	}
	return s[i : i+j]
}

// comment returns the index after a comment at i. encoding/xml ends it at
// the first "--", which must close it, and checks nothing else inside.
func (s *nzbScanner) comment(i int) int {
	for j := i + len("<!--"); ; j++ {
		j = s.find(j, '-')
		if s.at(j+1) == '-' {
			if s.at(j+2) != '>' {
				panic(nzbShape{})
			}
			return j + 3
		}
	}
}

// prolog reads the document up to and including the root start tag.
func (s *nzbScanner) prolog() (i int, ok bool) {
	defer func() {
		if recover() != nil {
			ok = false
		}
	}()
	if s.lit(0, "<?xml") && isXMLSpace(s.at(5)) {
		end := 5
		for {
			end = s.find(end, '?')
			if s.at(end+1) == '>' {
				break
			}
			end++
		}
		content := string(s.buf[5:end])
		if v := xmlProcInst("version", content); v != "" && v != "1.0" {
			return 0, false
		}
		if e := xmlProcInst("encoding", content); e != "" && !strings.EqualFold(e, "utf-8") {
			return 0, false
		}
		i = end + 2
	}
	for {
		i = s.space(i)
		if s.lit(i, "<!--") {
			i = s.comment(i)
			continue
		}
		if s.lit(i, "<!DOCTYPE") {
			var quote byte
			for i += len("<!DOCTYPE"); ; i++ {
				b := s.at(i)
				if quote != 0 {
					if b == quote {
						quote = 0
					}
					continue
				}
				if b == '"' || b == '\'' {
					quote = b
				} else if b == '[' || b == '<' {
					return 0, false // an internal subset
				} else if b == '>' {
					i++
					break
				}
			}
			continue
		}
		if !s.open(i, "nzb") {
			return 0, false
		}
		i, empty := s.attrs(i+len("<nzb"), nil, nil)
		return i, !empty
	}
}

var (
	nzbMetaAttrs    = []string{"type"}
	nzbFileAttrs    = []string{"subject"}
	nzbSegmentAttrs = []string{"bytes", "number"}
)

func (s *nzbScanner) head(i int) ([]nzbMeta, int) {
	i, empty := s.attrs(i+len("<head"), nil, nil)
	var metas []nzbMeta
	if empty {
		return metas, i
	}
	for {
		i = s.space(i)
		if s.isClose(i, "head") {
			return metas, s.close(i, "head")
		}
		if !s.open(i, "meta") {
			panic(nzbShape{})
		}
		var m nzbMeta
		i, empty = s.attrs(i+len("<meta"), nzbMetaAttrs, func(_ int, v []byte) { m.Type = string(v) })
		if !empty {
			var value []byte
			value, i = s.text(i)
			m.Value = string(value)
			i = s.close(i, "meta")
		}
		metas = append(metas, m)
	}
}

func (s *nzbScanner) file(i int, newSegments func() boundedNZBSegments) (string, []nzbSegment, int) {
	var subject string
	i, empty := s.attrs(i+len("<file"), nzbFileAttrs, func(_ int, v []byte) { subject = string(v) })
	segments := newSegments()
	if empty {
		return subject, segments.items, i
	}
	listed := false
	for {
		i = s.space(i)
		switch {
		case s.isClose(i, "file"):
			return subject, segments.items, s.close(i, "file")
		case s.open(i, "groups"):
			i = s.groups(i)
		case s.open(i, "segments"):
			if listed {
				panic(nzbShape{}) // the decoder appends a second list to the first
			}
			listed = true
			i = s.segments(i, &segments)
		default:
			panic(nzbShape{})
		}
	}
}

func (s *nzbScanner) groups(i int) int {
	i, empty := s.attrs(i+len("<groups"), nil, nil)
	if empty {
		return i
	}
	for {
		i = s.space(i)
		if s.isClose(i, "groups") {
			return s.close(i, "groups")
		}
		if !s.open(i, "group") {
			panic(nzbShape{})
		}
		if i, empty = s.attrs(i+len("<group"), nil, nil); !empty {
			_, i = s.text(i)
			i = s.close(i, "group")
		}
	}
}

func (s *nzbScanner) segments(i int, list *boundedNZBSegments) int {
	i, empty := s.attrs(i+len("<segments"), nil, nil)
	if empty {
		return i
	}
	for {
		i = s.space(i)
		if s.isClose(i, "segments") {
			return s.close(i, "segments")
		}
		if !s.open(i, "segment") {
			panic(nzbShape{})
		}
		if err := list.take(); err != nil {
			panic(nzbStop{fmt.Errorf("invalid NZB file: %w", err)})
		}
		var size, number []byte
		i, empty = s.attrs(i+len("<segment"), nzbSegmentAttrs, func(k int, v []byte) {
			if k == 0 {
				size = v
			} else {
				number = v
			}
		})
		var id []byte
		if !empty {
			id, i = s.text(i)
			i = s.close(i, "segment")
		}
		list.items = append(list.items, nzbSegment{
			Number: int(nzbInt(number, strconv.IntSize)),
			Bytes:  nzbInt(size, 64),
			ID:     string(id),
		})
	}
}

type nzbStep int

const (
	nzbStepDecoder nzbStep = iota
	nzbStepHead
	nzbStepFile
	nzbStepEnd
	nzbStepComment
)

type nzbChild struct {
	metas    []nzbMeta
	subject  string
	segments []nzbSegment
}

// child reads the root's next child at i. Any stop, a bug included, leaves
// the element to the decoder.
func (s *nzbScanner) child(i int, h nzbFastHooks) (next int, step nzbStep, c nzbChild, err error) {
	s.mark = i
	defer func() {
		if v := recover(); v != nil {
			if stop, ok := v.(nzbStop); ok {
				err = stop.err
				return
			}
			next, step, c = i, nzbStepDecoder, nzbChild{}
		}
	}()
	j := s.space(i)
	switch {
	case s.isClose(j, "nzb"):
		return s.close(j, "nzb"), nzbStepEnd, c, nil
	case s.lit(j, "<!--"):
		return s.comment(j), nzbStepComment, c, nil
	case s.open(j, "head"):
		c.metas, next = s.head(j)
		return next, nzbStepHead, c, nil
	case s.open(j, "file"):
		if err := h.fileLimit(); err != nil {
			return i, nzbStepDecoder, c, err
		}
		c.subject, c.segments, next = s.file(j, h.segments)
		return next, nzbStepFile, c, nil
	}
	return i, nzbStepDecoder, c, nil
}

// onlySpaceLeft reports whether nothing but whitespace follows i.
func (s *nzbScanner) onlySpaceLeft(i int) bool {
	s.mark = i
	for {
		for ; i < len(s.buf); i++ {
			if !isXMLSpace(s.buf[i]) {
				return false
			}
		}
		if !s.more() {
			return s.err == io.EOF
		}
	}
}

type nzbErrorReader struct{ err error }

func (r nzbErrorReader) Read([]byte) (int, error) { return 0, r.err }

// rest is the document from i for the decoder: what the fast reader buffered,
// then the reader, or the error the reader already returned.
func (s *nzbScanner) rest(i int, prefix string) io.Reader {
	tail := s.r
	if s.err != nil {
		tail = nzbErrorReader{s.err}
	}
	return io.MultiReader(strings.NewReader(prefix), bytes.NewReader(s.buf[i:]), tail)
}

// readNZBFast reads the document while it keeps to the plain shape. It returns
// nil when it read all of it; otherwise the rest for the decoder, with root
// false when the decoder still has to read a root element (the real one, or a
// stand-in for the one already read when the fast reader stops inside it).
func readNZBFast(r io.Reader, h nzbFastHooks) (io.Reader, bool, error) {
	s := &nzbScanner{r: r, buf: make([]byte, 0, nzbScanChunk)}
	i, ok := s.prolog()
	if !ok {
		return s.rest(0, ""), false, nil
	}
	for {
		if i > nzbScanChunk && i >= len(s.buf)/2 {
			n := copy(s.buf, s.buf[i:])
			s.buf, i = s.buf[:n], 0
		}
		next, step, c, err := s.child(i, h)
		if err != nil {
			return nil, false, err
		}
		switch step {
		case nzbStepDecoder:
			return s.rest(i, "<nzb>"), false, nil
		case nzbStepEnd:
			if s.onlySpaceLeft(next) {
				return nil, true, nil
			}
			return s.rest(next, ""), true, nil
		case nzbStepHead:
			h.head(c.metas)
		case nzbStepFile:
			if err := h.file(c.subject, c.segments); err != nil {
				return nil, false, err
			}
		}
		i = next
	}
}

// nzbMessageID is strings.Trim(strings.TrimSpace(id), "<>"): a large NZB has
// one per article, and the generic cutset work cost a weak CPU a third of a
// second per remux. A non-ASCII id takes the generic path.
func nzbMessageID(id string) string {
	for i := 0; i < len(id); i++ {
		if id[i] >= utf8.RuneSelf {
			return strings.Trim(strings.TrimSpace(id), "<>")
		}
	}
	start, end := 0, len(id)
	for start < end && isASCIISpace(id[start]) {
		start++
	}
	for end > start && isASCIISpace(id[end-1]) {
		end--
	}
	for start < end && (id[start] == '<' || id[start] == '>') {
		start++
	}
	for end > start && (id[end-1] == '<' || id[end-1] == '>') {
		end--
	}
	return id[start:end]
}

// isASCIISpace is unicode.IsSpace for a byte below 0x80, as TrimSpace uses it.
func isASCIISpace(b byte) bool {
	return b == ' ' || b == '\t' || b == '\n' || b == '\v' || b == '\f' || b == '\r'
}

// nzbForbiddenInID is strings.ContainsAny(id, "\r\n\x00<>"): a message-id
// with any of them cannot go into an NNTP command.
func nzbForbiddenInID(id string) bool {
	for i := 0; i < len(id); i++ {
		switch id[i] {
		case '\r', '\n', 0, '<', '>':
			return true
		}
	}
	return false
}
