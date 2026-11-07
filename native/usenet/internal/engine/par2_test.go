package engine

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/md5"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// testdata/par2_rar4.tar.gz was made with RAR 6.23 and par2cmdline 0.8.1 from
// mkvPayload(60007) as show.s01e01.mkv:
//
//	rar a -m0 -ma4 -vn -v8k show.s01e01.rar show.s01e01.mkv
//	par2 create -s4096 -r10 show.s01e01.par2 show.s01e01.r*
func par2Fixture(t testing.TB) map[string][]byte {
	t.Helper()
	f, err := os.Open(filepath.Join("..", "..", "testdata", "par2_rar4.tar.gz"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	out := map[string][]byte{}
	tr := tar.NewReader(gz)
	for {
		h, err := tr.Next()
		if err == io.EOF {
			return out
		}
		if err != nil {
			t.Fatal(err)
		}
		if h.Typeflag == tar.TypeReg {
			b, _ := io.ReadAll(tr)
			out[filepath.Base(h.Name)] = b
		}
	}
}

// obfuscatedPost posts the fixture's RAR volumes under random names in a
// shuffled order, the way fully obfuscated uploads look.
func obfuscatedPost(fixtures map[string][]byte, par2 string) []inputFile {
	var volumes, recovery []string
	for name := range fixtures {
		if strings.HasSuffix(name, ".par2") {
			recovery = append(recovery, name)
		} else {
			volumes = append(volumes, name)
		}
	}
	sort.Strings(volumes)
	sort.Strings(recovery)
	order := []int{5, 2, 7, 0, 3, 6, 1, 4}
	var in []inputFile
	for k, i := range order {
		data := fixtures[volumes[i]]
		vol := inputFile{name: fmt.Sprintf("%x", md5.Sum([]byte(volumes[i])))[:24], data: data}
		for left := len(data); left > 0; left -= 3000 {
			vol.sizes = append(vol.sizes, min(left, 3000))
		}
		in = append(in, vol)
		if k == 3 && par2 != "" {
			for _, name := range recovery {
				if par2 == "obfuscated" {
					in = append(in, inputFile{fmt.Sprintf("%x", md5.Sum([]byte(name)))[:20], fixtures[name], nil})
				} else {
					in = append(in, inputFile{name, fixtures[name], nil})
				}
			}
		}
	}
	return in
}

func TestPAR2RecoversObfuscatedRAR4Order(t *testing.T) {
	fixtures := par2Fixture(t)
	want := mkvPayload(60007)
	for _, par2 := range []string{"named", "obfuscated"} {
		for _, sel := range []Selection{{}, {Season: 1, Episode: 1}} {
			t.Run(fmt.Sprintf("%s/episode=%d", par2, sel.Episode), func(t *testing.T) {
				files, _, _ := setup(t, obfuscatedPost(fixtures, par2), 0, 4, 4)
				c, err := Select(context.Background(), files, sel)
				if err != nil {
					t.Fatal(err)
				}
				if c.Name != "show.s01e01.mkv" || c.Size != int64(len(want)) {
					t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
				}
				if got := readAllContent(t, c); !bytes.Equal(got, want) {
					t.Fatalf("content mismatch (%d bytes)", len(got))
				}
			})
		}
	}
}

func TestObfuscatedRAR4WithoutPAR2StillFails(t *testing.T) {
	files, _, _ := setup(t, obfuscatedPost(par2Fixture(t), ""), 0, 4, 4)
	if _, err := Select(context.Background(), files, Selection{}); err == nil {
		t.Fatal("unordered RAR4 volumes were accepted without PAR2 names")
	}
}

// A named set that is simply incomplete must fail as before, without reading
// one article per file for a PAR2 match that cannot help.
func TestNamedBrokenSetSkipsPAR2Recovery(t *testing.T) {
	fixtures := par2Fixture(t)
	var in []inputFile
	for _, name := range []string{"show.s01e01.rar", "show.s01e01.r00", "show.s01e01.r02", "show.s01e01.r03", "show.s01e01.par2"} {
		in = append(in, inputFile{name, fixtures[name], nil})
	}
	files, _, server := setup(t, in, 0, 4, 4)
	if _, err := Select(context.Background(), files, Selection{}); err == nil {
		t.Fatal("a set missing .r01 was accepted")
	}
	if bodies := server.Counters().Bodies; bodies > 3 {
		t.Fatalf("selection read %d articles; PAR2 recovery ran on a named set", bodies)
	}
}

func TestParsePAR2SkipsCorruptPackets(t *testing.T) {
	index := par2Fixture(t)["show.s01e01.par2"]
	descs := parsePAR2(index)
	if len(descs) != 8 {
		t.Fatalf("%d file descriptions, want 8", len(descs))
	}
	damaged := append([]byte(nil), index...)
	i := bytes.Index(damaged, par2FileDescType)
	damaged[i+64+60] ^= 0xff // inside the first description's name
	if got := parsePAR2(damaged); len(got) != 7 {
		t.Fatalf("%d descriptions from a damaged index, want 7", len(got))
	}
}
