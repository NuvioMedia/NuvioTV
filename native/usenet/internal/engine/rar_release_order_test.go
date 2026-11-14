package engine

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"strings"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// setupSubjects posts in[i] with subject number order[i] (`[n/N] - "name"`),
// or without one when order[i] is 0.
func setupSubjects(t testing.TB, in []inputFile, order []int) ([]*File, *nntpserver.Server) {
	t.Helper()
	var x strings.Builder
	x.WriteString(`<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">`)
	articles := map[string][]byte{}
	for i, f := range in {
		sizes := []int{len(f.data)}
		subject := fmt.Sprintf(`"%s" yEnc (1/1)`, f.name)
		if order[i] > 0 {
			subject = fmt.Sprintf(`[%d/%d] - "%s" yEnc (1/1)`, order[i], len(in), f.name)
		}
		fmt.Fprintf(&x, `<file subject='%s' date="1"><groups><group>alt.test</group></groups><segments>`, subject)
		id := fmt.Sprintf("o%d-s0@test", i)
		wire := encodePart(f.name, f.data, 0, int64(len(f.data)), 1, len(sizes))
		articles[id] = wire
		fmt.Fprintf(&x, `<segment bytes="%d" number="1">%s</segment></segments></file>`, len(wire), id)
	}
	x.WriteString(`</nzb>`)
	s, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := nntppool.NewClient(context.Background(), []nntppool.Provider{{Host: s.Addr(), Connections: 4, Inflight: 4, StreamInflight: 4, SkipPing: true}}, nntppool.WithStatProbe(false))
	if err != nil {
		t.Fatal(err)
	}
	store := NewStore(context.Background(), pool, 32<<20)
	t.Cleanup(func() { store.Close(); pool.Close(); s.Close() })
	files, err := ParseNZB(strings.NewReader(x.String()), store)
	if err != nil {
		t.Fatal(err)
	}
	return files, s
}

// Real posts shuffle volume names in small windows (part01 holds volume 3,
// part02 volume 0) while their subjects number them in volume order.
func shuffledNames(count int) []int {
	perm := make([]int, count)
	for i := range perm {
		perm[i] = i
	}
	copy(perm[0:], []int{3, 0, 1, 2})
	copy(perm[4:], []int{5, 6, 4})
	copy(perm[9:], []int{11, 10, 9})
	copy(perm[count-4:], []int{count - 3, count - 1, count - 4, count - 2})
	return perm
}

func TestRARShuffledNamesFollowReleaseOrder(t *testing.T) {
	const count = 60
	for _, tc := range []struct {
		name      string
		subjects  func(volume, position int) int
		predicted bool
	}{
		{"release order", func(v, p int) int { return v + 1 }, true},
		{"shuffled subjects", func(v, p int) int { return p + 1 }, false},
		{"no subject numbers", func(v, p int) int { return 0 }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			vols, want, starts := regularRARSet(5, count, 2048, 31)
			perm := shuffledNames(count)
			in := make([]inputFile, count)
			order := make([]int, count)
			for p, v := range perm {
				in[p] = inputFile{fmt.Sprintf("pack.part%03d.rar", p+1), vols[v].data, nil}
				order[p] = tc.subjects(v, p)
			}
			files, server := setupSubjects(t, in, order)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			startup := server.Counters().Bodies
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			r.Seek(-17, io.SeekEnd)
			got, err := io.ReadAll(r)
			if err != nil || !bytes.Equal(got, want[len(want)-17:]) {
				t.Fatalf("EOF bytes differ: %v", err)
			}
			if (c.predicted != nil) != tc.predicted || c.cursor.unordered == tc.predicted {
				t.Fatalf("predicted=%v unordered=%v", c.predicted != nil, c.cursor.unordered)
			}
			if tc.predicted {
				// The second volume and the last, as for a set named in order.
				if eof := server.Counters().Bodies - startup; eof != 2 {
					t.Fatalf("EOF read fetched %d articles, want 2", eof)
				}
				before := server.Counters().Bodies
				off := starts[40] + 11
				r.Seek(int64(off), io.SeekStart)
				buf := make([]byte, 37)
				if _, err := io.ReadFull(r, buf); err != nil || !bytes.Equal(buf, want[off:off+37]) {
					t.Fatalf("seek bytes differ: %v", err)
				}
				if n := server.Counters().Bodies - before; n != 1 {
					t.Fatalf("a seek into the middle fetched %d articles, want 1", n)
				}
			}
			r.Seek(0, io.SeekStart)
			got, err = io.ReadAll(r)
			if err != nil || !bytes.Equal(got, want) {
				t.Fatalf("full read differs: %v", err)
			}
		})
	}
}
