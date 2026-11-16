package engine

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"math/rand/v2"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// The fast NZB reader must give exactly what encoding/xml gives: the same
// files, segments and password, or the same error. Each document is parsed
// both ways, held in memory and spilled to a cache index.

type nzbOutcome struct {
	err     string
	spilled bool
	files   []string
	spill   []string
}

var nzbErrorLine = regexp.MustCompile(`line \d+`)

func nzbDump(files []*File) []string {
	out := make([]string, 0, len(files))
	for _, f := range files {
		damaged := ""
		if f.damaged != nil {
			damaged = f.damaged.Error()
		}
		var segs strings.Builder
		for _, s := range f.segments {
			fmt.Fprintf(&segs, "%q/%d ", s.id, s.wire)
		}
		out = append(out, fmt.Sprintf("i=%d name=%q order=%d pw=%q size=%d exact=%v damaged=%q prefix=%v segs=%s",
			f.Index, f.Name, f.order, f.password, f.size, f.exact, damaged, f.prefix, segs.String()))
	}
	return out
}

func parseNZBWith(fast bool, doc []byte, spill *recordingSpill) nzbOutcome {
	old := fastNZBReader
	fastNZBReader = fast
	defer func() { fastNZBReader = old }()
	var target nzbSpill
	if spill != nil {
		target = spill
	}
	files, spilled, err := parseNZB(bytes.NewReader(doc), nil, target)
	o := nzbOutcome{spilled: spilled, files: nzbDump(files)}
	if err != nil {
		// After the fast reader stops, the decoder counts lines from there.
		o.err = nzbErrorLine.ReplaceAllString(err.Error(), "line N")
	}
	if spill != nil {
		o.spill = append([]string{fmt.Sprint(spill.started)}, spill.names...)
	}
	return o
}

func requireSameNZB(t testing.TB, doc []byte) {
	t.Helper()
	for _, spilling := range []bool{false, true} {
		var fastSpill, xmlSpill *recordingSpill
		if spilling {
			fastSpill, xmlSpill = &recordingSpill{}, &recordingSpill{}
		}
		fast := parseNZBWith(true, doc, fastSpill)
		want := parseNZBWith(false, doc, xmlSpill)
		if fmt.Sprintf("%#v", fast) != fmt.Sprintf("%#v", want) {
			t.Fatalf("spill=%v: fast reader differs from encoding/xml for %q\nfast: %#v\nxml:  %#v", spilling, doc, fast, want)
		}
	}
}

func nzbTestFile(subject string, segments ...string) string {
	return `<file poster="poster &lt;p@x&gt;" date="1660023779" subject="` + subject + `">` +
		`<groups><group>alt.binaries.test</group></groups><segments>` + strings.Join(segments, "") + `</segments></file>`
}

func nzbTestSegment(size, number, id string) string {
	return `<segment bytes="` + size + `" number="` + number + `">` + id + `</segment>`
}

func nzbTestDoc(head string, files ...string) string {
	return `<?xml version="1.0" encoding="UTF-8"?>` + "\n" +
		`<!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">` + "\n" +
		`<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">` + "\n" + head + strings.Join(files, "\n") + "\n" +
		`<!-- newznab 2022-08-09 07:11:02 -->` + "\n</nzb>\n"
}

func nzbTestSubject(k, n int) string {
	return fmt.Sprintf(`[%d/%d] - &quot;ab12cd.7z.%03d&quot; yEnc 2048 (1/2)`, k, n, k)
}

var nzbTestPlain = nzbTestDoc(`<head><meta type="title">x</meta><meta type="password">s3cr&amp;t</meta></head>`+"\n",
	nzbTestFile(nzbTestSubject(1, 2), nzbTestSegment("740688", "1", "AbC-1@nyuu"), nzbTestSegment("512", "2", "AbC-2@nyuu")),
	nzbTestFile(nzbTestSubject(2, 2), nzbTestSegment("740688", "2", "DeF-2@nyuu"), nzbTestSegment("740688", "1", "DeF-1@nyuu")))

func TestFastNZBReaderReadsThePlainShape(t *testing.T) {
	files := 0
	rest, _, err := readNZBFast(strings.NewReader(nzbTestPlain), nzbFastHooks{
		head:      func([]nzbMeta) {},
		fileLimit: func() error { return nil },
		segments:  func() boundedNZBSegments { return boundedNZBSegments{remaining: 1 << 20} },
		file:      func(string, []nzbSegment) error { files++; return nil },
	})
	if rest != nil || err != nil || files != 2 {
		t.Fatalf("rest = %v, err = %v, files = %d: the plain shape went to the decoder", rest != nil, err, files)
	}
	requireSameNZB(t, []byte(nzbTestPlain))
}

func TestFastNZBReaderMatchesEncodingXML(t *testing.T) {
	seg := nzbTestSegment("740688", "1", "AbC-1@nyuu")
	sub := nzbTestSubject(1, 1)
	file := nzbTestFile(sub, seg)
	replace := func(old, new string) string { return strings.Replace(nzbTestPlain, old, new, 1) }
	cases := map[string]string{
		"plain":                   nzbTestPlain,
		"no declaration":          strings.SplitN(nzbTestPlain, "\n", 2)[1],
		"no doctype":              replace(`<!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">`, ""),
		"single quoted decl":      replace(`version="1.0" encoding="UTF-8"`, `version='1.0' encoding='utf-8'`),
		"latin-1":                 replace(`encoding="UTF-8"`, `encoding="ISO-8859-1"`) + "",
		"latin-1 byte":            strings.Replace(replace(`encoding="UTF-8"`, `encoding="ISO-8859-1"`), "title", "t\xe9tle", 1),
		"version 1.1":             replace(`version="1.0"`, `version="1.1"`),
		"spaced decl":             replace(`version="1.0" encoding="UTF-8"`, `version = "1.1" encoding = "ISO-8859-1"`),
		"bom":                     "\xef\xbb\xbf" + nzbTestPlain,
		"internal subset":         replace(`"http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">`, `"x" [<!ENTITY e "AbC">]>`),
		"declared entity":         strings.Replace(replace(`"http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">`, `"x" [<!ENTITY e "AbC">]>`), "AbC-1", "&e;-1", 1),
		"comment before root":     replace(`<nzb `, "<!-- hi -->\n<nzb "),
		"comment between files":   replace("</file>\n", "</file><!-- between -->\n"),
		"comment in segments":     replace(`</segment><segment`, `</segment><!-- in --><segment`),
		"bad comment":             replace(`newznab 2022`, `newznab -- 2022`),
		"short comment":           replace(`<!-- newznab 2022-08-09 07:11:02 -->`, `<!---->`),
		"three dash comment":      replace(`<!-- newznab 2022-08-09 07:11:02 -->`, `<!--->`),
		"cdata id":                replace(`AbC-1@nyuu`, `<![CDATA[AbC-1@nyuu]]>`),
		"amp id":                  replace(`AbC-1@nyuu`, `A&amp;bC-1@nyuu`),
		"decimal reference":       replace(`AbC-1@nyuu`, `AbC-1&#64;nyuu`),
		"hex reference":           replace(`AbC-1@nyuu`, `AbC-1&#x40;nyuu`),
		"upper hex reference":     replace(`AbC-1@nyuu`, `AbC-1&#X40;nyuu`),
		"empty reference":         replace(`AbC-1@nyuu`, `AbC-1&#;nyuu`),
		"zero reference":          replace(`AbC-1@nyuu`, `AbC-1&#0;nyuu`),
		"surrogate reference":     replace(`AbC-1@nyuu`, `AbC-1&#xD800;nyuu`),
		"non-ascii reference":     replace(`AbC-1@nyuu`, `AbC-1&#233;nyuu`),
		"long reference":          replace(`AbC-1@nyuu`, `AbC-1&#x00000000040;nyuu`),
		"unknown entity":          replace(`AbC-1@nyuu`, `AbC-1&foo;nyuu`),
		"unterminated entity":     replace(`AbC-1@nyuu`, `AbC-1&amp nyuu`),
		"bare ampersand":          replace(`AbC-1@nyuu`, `AbC-1 & nyuu`),
		"crlf":                    strings.ReplaceAll(nzbTestPlain, "\n", "\r\n"),
		"cr in id":                replace(`AbC-1@nyuu`, "AbC-1\r@nyuu"),
		"crlf in id":              replace(`AbC-1@nyuu`, "AbC-1\r\n@nyuu"),
		"cr in subject":           replace(`[1/2]`, "[1/2]\r"),
		"newline in id":           replace(`AbC-1@nyuu`, "\n  AbC-1@nyuu\n"),
		"tab in subject":          replace(`[1/2] -`, "[1/2]\t-"),
		"utf-8 subject":           replace(`ab12cd.7z.001`, `ab12cdé日本.7z.001`),
		"invalid utf-8":           replace(`ab12cd.7z.001`, "ab12cd\xff.7z.001"),
		"truncated utf-8":         replace(`ab12cd.7z.001`, "ab12cd\xc3.7z.001"),
		"fffe":                    replace(`ab12cd.7z.001`, "ab12cd\xef\xbf\xbe.7z.001"),
		"control in subject":      replace(`ab12cd.7z.001`, "ab12cd\x01.7z.001"),
		"control in poster":       replace(`poster &lt;`, "poster \x01&lt;"),
		"nul in id":               replace(`AbC-1@nyuu`, "AbC-1\x00@nyuu"),
		"del in id":               replace(`AbC-1@nyuu`, "AbC-1\x7f@nyuu"),
		"less than in attribute":  replace(`date="1660023779"`, `date="1<2"`),
		"cdata end in attribute":  replace(`date="1660023779"`, `date="]]>"`),
		"cdata end in text":       replace(`AbC-1@nyuu`, `AbC-1]]>@nyuu`),
		"brackets in text":        replace(`AbC-1@nyuu`, `AbC-1]]@nyuu`),
		"greater than in text":    replace(`AbC-1@nyuu`, `AbC-1>@nyuu`),
		"unquoted attribute":      replace(`bytes="740688" number="1"`, `bytes=740688 number="1"`),
		"attribute without value": replace(`bytes="740688" number="1"`, `bytes number="1"`),
		"repeated subject":        replace(`subject="`, `subject="x" subject="`),
		"repeated number":         replace(`number="1"`, `number="1" number="2"`),
		"repeated unused":         replace(`date="1660023779"`, `date="1" date="2"`),
		"prefixed attribute":      replace(`date="1660023779"`, `x:date="1"`),
		"prefixed subject":        replace(` subject="`, ` x:subject="`),
		"prefixed elements":       strings.ReplaceAll(strings.ReplaceAll(nzbTestPlain, "<file", "<n:file"), "</file", "</n:file"),
		"prefixed root":           strings.ReplaceAll(strings.Replace(strings.ReplaceAll(nzbTestPlain, "<nzb", "<n:nzb"), `xmlns=`, `xmlns:n=`, 1), "</nzb", "</n:nzb"),
		"default namespace file":  replace(`<file `, `<file xmlns="urn:x" `),
		"spaced attributes":       replace(`bytes="740688" number="1">`, "bytes = \"740688\"\n\tnumber='1' >"),
		"spaced end tags":         strings.ReplaceAll(nzbTestPlain, "</segment>", "</segment \n>"),
		"adjacent attributes":     replace(`bytes="740688" number="1"`, `bytes="740688"number="1"`),
		"digit attribute name":    replace(`date="1660023779"`, `1date="1"`),
		"upper case file":         strings.ReplaceAll(nzbTestPlain, "file", "FILE"),
		"number spaces":           replace(`number="1"`, `number=" 1 "`),
		"number empty":            replace(`number="1"`, `number=""`),
		"number negative":         replace(`number="1"`, `number="-1"`),
		"number plus":             replace(`number="1"`, `number="+1"`),
		"number exponent":         replace(`number="1"`, `number="1e3"`),
		"number hex":              replace(`number="1"`, `number="0x1"`),
		"number entity":           replace(`number="1"`, `number="&#49;"`),
		"number int32 overflow":   replace(`number="1"`, `number="4294967297"`),
		"number int64 overflow":   replace(`number="1"`, `number="99999999999999999999"`),
		"bytes max":               replace(`bytes="740688"`, `bytes="9223372036854775807"`),
		"bytes sum overflow":      strings.Replace(replace(`bytes="740688"`, `bytes="9223372036854775807"`), `bytes="512"`, `bytes="9223372036854775807"`, 1),
		"bytes zero":              replace(`bytes="740688"`, `bytes="0"`),
		"missing number":          replace(`number="1"`, ``),
		"missing bytes":           replace(`bytes="740688"`, ``),
		"missing subject":         replace(` subject="`+nzbTestSubject(1, 2)+`"`, ``),
		"empty subject":           replace(nzbTestSubject(1, 2), ``),
		"self-closing segment":    replace(`<segment bytes="740688" number="1">AbC-1@nyuu</segment>`, `<segment bytes="740688" number="1"/>`),
		"self-closing file":       nzbTestDoc("", `<file subject="`+sub+`"/>`, file),
		"self-closing segments":   nzbTestDoc("", `<file subject="`+sub+`"><segments/></file>`, file),
		"self-closing groups":     nzbTestDoc("", strings.Replace(file, `<groups><group>alt.binaries.test</group></groups>`, `<groups/>`, 1)),
		"self-closing group":      nzbTestDoc("", strings.Replace(file, `<group>alt.binaries.test</group>`, `<group/>`, 1)),
		"self-closing head":       nzbTestDoc("<head/>", file),
		"self-closing meta":       nzbTestDoc(`<head><meta type="password"/></head>`, file),
		"no groups":               nzbTestDoc("", strings.Replace(file, `<groups><group>alt.binaries.test</group></groups>`, ``, 1)),
		"segments before groups":  nzbTestDoc("", `<file subject="`+sub+`"><segments>`+seg+`</segments><groups><group>g</group></groups></file>`),
		"two segment lists":       nzbTestDoc("", `<file subject="`+sub+`"><segments>`+seg+`</segments><segments>`+nzbTestSegment("9", "2", "x@y")+`</segments></file>`),
		"unknown top element":     replace("</head>\n", "</head>\n<extra a=\"1\">x<y/></extra>\n"),
		"unknown file child":      replace(`<groups>`, `<poster>p</poster><groups>`),
		"unknown segments child":  replace(`</segment><segment`, `</segment><foo/><segment`),
		"unknown head child":      replace(`<meta type="title">`, `<title>t</title><meta type="title">`),
		"element in segment":      replace(`AbC-1@nyuu`, `AbC-1<b/>@nyuu`),
		"element in meta":         replace(`s3cr&amp;t`, `s3cr<b/>t`),
		"meta without type":       replace(`<meta type="password">`, `<meta>`),
		"meta password case":      replace(`type="password"`, `type="PassWord"`),
		"meta password spaces":    replace(`s3cr&amp;t`, "  s3cr&amp;t\n"),
		"meta password long":      replace(`s3cr&amp;t`, strings.Repeat("p", 1025)),
		"two passwords":           replace(`<meta type="title">x</meta>`, `<meta type="password">first</meta>`),
		"text at top":             replace("</head>\n", "</head>\njunk\n"),
		"text in file":            replace(`<groups>`, `junk<groups>`),
		"text in segments":        replace(`</segment><segment`, `</segment>junk<segment`),
		"text in head":            replace(`<meta type="title">`, `junk<meta type="title">`),
		"wrong root":              strings.ReplaceAll(nzbTestPlain, "nzb", "nzbx"),
		"self-closing root":       `<?xml version="1.0"?><nzb/>`,
		"empty":                   ``,
		"whitespace only":         " \n\t ",
		"after root text":         nzbTestPlain + "junk",
		"after root file":         nzbTestPlain + file,
		"after root comment":      nzbTestPlain + "<!-- end -->\n",
		"second root":             nzbTestPlain + nzbTestPlain,
		"mismatched end":          replace(`</segments></file>`, `</segments></fil>`),
		"unclosed file":           replace("</file>\n", "\n"),
		"no files":                nzbTestDoc(`<head></head>`),
		"unsorted segments":       nzbTestDoc("", nzbTestFile(sub, nzbTestSegment("5", "3", "c@x"), nzbTestSegment("5", "1", "a@x"), nzbTestSegment("5", "2", "b@x"))),
		"duplicate numbers":       nzbTestDoc("", nzbTestFile(sub, nzbTestSegment("5", "1", "a@x"), nzbTestSegment("5", "1", "b@x"))),
		"angle id":                replace(`AbC-1@nyuu`, `&lt;AbC-1@nyuu&gt;`),
		"entities in name":        replace(`ab12cd.7z.001`, `a&lt;b&gt;c&apos;d&amp;e&#65;&#x42;.7z.001`),
		"entities in password":    replace(`s3cr&amp;t`, `a&lt;b&gt;c&apos;d&quot;e&#65;`),
		"entities in id":          replace(`AbC-1@nyuu`, `A&lt;b&gt;C-1@nyuu`),
		"empty id":                replace(`AbC-1@nyuu`, ``),
		"long id":                 replace(`AbC-1@nyuu`, strings.Repeat("a", 999)+"@x"),
		"processing instruction":  replace("</head>\n", "</head>\n<?php x ?>\n"),
		"doctype in body":         replace("</head>\n", "</head>\n<!DOCTYPE x>\n"),
	}
	for name, doc := range cases {
		t.Run(name, func(t *testing.T) { requireSameNZB(t, []byte(doc)) })
	}
}

// Every prefix of a document fails or reads the same way.
func TestFastNZBReaderMatchesOnEveryTruncation(t *testing.T) {
	for _, doc := range []string{nzbTestPlain, strings.ReplaceAll(nzbTestPlain, "\n", "\r\n")} {
		for n := 0; n <= len(doc); n++ {
			requireSameNZB(t, []byte(doc[:n]))
		}
	}
}

// Random edits of plain documents, deterministic so that a failure repeats.
func TestFastNZBReaderMatchesOnMutations(t *testing.T) {
	seeds := []string{nzbTestPlain, strings.ReplaceAll(nzbTestPlain, "\n", "\r\n"),
		nzbTestDoc("", nzbTestFile(nzbTestSubject(1, 1), nzbTestSegment("1", "1", "a&amp;b@x")))}
	pieces := []string{"<", ">", "&", ";", `"`, "'", "/", "=", " ", "\r", "\n", "\t", "\x00", "\x01", "\xc3", "\xa9", "\xff",
		"]", "-", "!", "?", "#", "x", "1", "<!--", "-->", "--", "&amp;", "&#", "&#x", "]]>", "<![CDATA[", "<segment>",
		"</segment>", "</file>", "<file>", "</segments>", "<head>", "</nzb>", "<nzb>", ":", "xmlns", "number=\"2\"",
		"&lt;", "&gt;", "&apos;", "&quot;", "&#65;", "&#x42;"}
	rng := rand.New(rand.NewPCG(4426, 83))
	iterations := 20000
	if testing.Short() {
		iterations = 2000
	}
	for k := 0; k < iterations; k++ {
		doc := []byte(seeds[rng.IntN(len(seeds))])
		for edits := 1 + rng.IntN(3); edits > 0; edits-- {
			at := rng.IntN(len(doc) + 1)
			switch rng.IntN(4) {
			case 0: // insert
				doc = append(doc[:at:at], append([]byte(pieces[rng.IntN(len(pieces))]), doc[at:]...)...)
			case 1: // replace a byte
				if at < len(doc) {
					doc[at] = pieces[rng.IntN(len(pieces))][0]
				}
			case 2: // delete a run
				end := min(len(doc), at+1+rng.IntN(8))
				doc = append(doc[:at:at], doc[end:]...)
			case 3: // repeat a run
				end := min(len(doc), at+1+rng.IntN(40))
				doc = append(doc[:end:end], append(append([]byte(nil), doc[at:end]...), doc[end:]...)...)
			}
		}
		requireSameNZB(t, doc)
	}
}

// Documents past the in-memory bounds, a gzip body, an element larger than
// the fast reader buffers, and the file limit.
func TestFastNZBReaderMatchesAtTheBounds(t *testing.T) {
	var files []string
	for k := 1; k <= 30; k++ {
		var segs []string
		for n := 1; n <= 20; n++ {
			segs = append(segs, nzbTestSegment("740688", fmt.Sprint(n), fmt.Sprintf("s%d-%d@nyuu", k, n)))
		}
		files = append(files, nzbTestFile(nzbTestSubject(k, 30), segs...))
	}
	doc := []byte(nzbTestDoc(`<head><meta type="password">pw</meta></head>`, files...))
	t.Run("segment bound", func(t *testing.T) {
		withHeldNZBLimits(t, maxNZBBytes, 50)
		requireSameNZB(t, doc)
	})
	t.Run("byte bound", func(t *testing.T) {
		withHeldNZBLimits(t, 4096, maxNZBSegments)
		requireSameNZB(t, doc)
	})
	t.Run("refused spill", func(t *testing.T) {
		withHeldNZBLimits(t, maxNZBBytes, 50)
		fast := parseNZBWith(true, doc, &recordingSpill{refuse: 2})
		want := parseNZBWith(false, doc, &recordingSpill{refuse: 2})
		if fmt.Sprintf("%#v", fast) != fmt.Sprintf("%#v", want) {
			t.Fatalf("fast %#v\nxml %#v", fast, want)
		}
	})
	t.Run("gzip", func(t *testing.T) {
		var z bytes.Buffer
		w := gzip.NewWriter(&z)
		w.Write(doc)
		w.Close()
		requireSameNZB(t, z.Bytes())
	})
	t.Run("element past the buffer", func(t *testing.T) {
		old := nzbFastMaxElement
		nzbFastMaxElement = 2048
		t.Cleanup(func() { nzbFastMaxElement = old })
		requireSameNZB(t, doc)
	})
	t.Run("file limit", func(t *testing.T) {
		tiny := nzbTestFile(nzbTestSubject(1, 1), nzbTestSegment("1", "1", "a@x"))
		many := make([]string, 10001)
		for k := range many {
			many[k] = tiny
		}
		requireSameNZB(t, []byte(nzbTestDoc("", many...)))
	})
}

// NZB_CORPUS names a directory of real NZBs to compare, never committed.
func TestFastNZBReaderMatchesOnCorpus(t *testing.T) {
	dir := os.Getenv("NZB_CORPUS")
	if dir == "" {
		t.Skip("NZB_CORPUS not set")
	}
	paths, _ := filepath.Glob(filepath.Join(dir, "*.nzb"))
	if len(paths) == 0 {
		t.Fatal("no NZBs in", dir)
	}
	for _, path := range paths {
		doc, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		t.Run(filepath.Base(path), func(t *testing.T) { requireSameNZB(t, doc) })
	}
}

func FuzzFastNZBReader(f *testing.F) {
	f.Add([]byte(nzbTestPlain))
	f.Add([]byte(strings.ReplaceAll(nzbTestPlain, "\n", "\r\n")))
	f.Add([]byte(nzbTestDoc("", nzbTestFile(nzbTestSubject(1, 1), nzbTestSegment("1", "1", "a&amp;b@x")))))
	f.Fuzz(func(t *testing.T, doc []byte) {
		requireSameNZB(t, doc)
	})
}

// The message-id helpers are shared by both readers, so the comparisons above
// cannot see them: they are compared with the generic calls they replace.
func checkNZBMessageID(t *testing.T, id string) {
	t.Helper()
	if got, want := nzbMessageID(id), strings.Trim(strings.TrimSpace(id), "<>"); got != want {
		t.Fatalf("nzbMessageID(%q) = %q, want %q", id, got, want)
	}
	if got, want := nzbForbiddenInID(id), strings.ContainsAny(id, "\r\n\x00<>"); got != want {
		t.Fatalf("nzbForbiddenInID(%q) = %v, want %v", id, got, want)
	}
}

func TestNZBMessageIDMatchesTheGenericCalls(t *testing.T) {
	for _, id := range []string{"", " ", "<>", "><", "<a@b>", " <a@b> ", "< a@b >", "\t\n\v\f\r a@b \r\n", "<<a@b>>", "a<b@c",
		"a>b", "a\x00b", "a\rb", "a\nb", " a@b ", "\u0085<a@b>\u0085", "é<a@b>", "<a@b>é", "\xff<a>", "<\xff>", " < > ", "a"} {
		checkNZBMessageID(t, id)
	}
}

func FuzzNZBMessageID(f *testing.F) {
	for _, id := range []string{"<a@b>", "  <x>\t", "a\x00<b>\r\n"} {
		f.Add(id)
	}
	f.Fuzz(checkNZBMessageID)
}
