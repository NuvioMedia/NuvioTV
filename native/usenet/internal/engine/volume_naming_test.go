package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"strings"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// Volume naming seen in real NZBs: reposted copies of each volume, an old-style
// .rar first volume posted under a .partNN.rar name, obfuscated numeric names
// that do not follow volume order, a random name that happens to end in digits,
// and obfuscated PAR2 files interleaved with 7z volumes.

// withRAR4MainFlags sets the main header flags of a rar4Volume (0x0100 marks
// the first volume) and fixes its CRC.
func withRAR4MainFlags(v []byte, flags uint16) []byte {
	v = append([]byte(nil), v...)
	h := v[7:20]
	binary.LittleEndian.PutUint16(h[3:], flags)
	binary.LittleEndian.PutUint16(h, uint16(crc32.ChecksumIEEE(h[2:])))
	return v
}

// setupYEnc posts each file under its subject name with a different yEnc name,
// as obfuscating posters do.
func setupYEnc(t testing.TB, in []inputFile, yenc []string) []*File {
	t.Helper()
	var x strings.Builder
	x.WriteString(`<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">`)
	articles := map[string][]byte{}
	for i, f := range in {
		sizes := f.sizes
		if len(sizes) == 0 {
			for left := len(f.data); left > 0; left -= min(left, 16<<10) {
				sizes = append(sizes, min(left, 16<<10))
			}
		}
		fmt.Fprintf(&x, `<file subject='"%s" yEnc (1/%d)' date="1"><groups><group>alt.test</group></groups><segments>`, f.name, len(sizes))
		off := 0
		for j, size := range sizes {
			id := fmt.Sprintf("y%d-s%d@test", i, j)
			wire := encodePart(yenc[i], f.data[off:off+size], int64(off), int64(len(f.data)), j+1, len(sizes))
			articles[id] = wire
			fmt.Fprintf(&x, `<segment bytes="%d" number="%d">%s</segment>`, len(wire), j+1, id)
			off += size
		}
		x.WriteString(`</segments></file>`)
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
	return files
}

func selectAndRead(t *testing.T, files []*File, wantName string, want []byte) {
	t.Helper()
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != wantName || c.Size != int64(len(want)) {
		t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
	}
	if got := readAllContent(t, c); !bytes.Equal(got, want) {
		t.Fatalf("content mismatch (%d bytes)", len(got))
	}
}

func TestRepostedVolumesKeepIntactCopy(t *testing.T) {
	want := payload(240000)
	first := rar4Volume("movie.mkv", want[:120000], len(want), 2, false)
	files := damagedSetup(t, []inputFile{
		{"x.part1.rar", first, twoSegments(len(first))}, // NZB lost a segment of this copy.
		{"x.part1.rar", first, nil},
		{"x.part2.rar", rar4Volume("movie.mkv", want[120000:], len(want), 1, false), nil},
		{"x.part2.rar", rar4Volume("movie.mkv", want[120000:], len(want), 1, false), nil},
	}, 0)
	selectAndRead(t, files, "movie.mkv", want)
}

func TestRenamedFirstVolumeJoinsRollSet(t *testing.T) {
	want := payload(300000)
	files := damagedSetup(t, []inputFile{
		{"Show.S01E01.part01.rar", rar4Volume("Show.S01E01.subs.rar", payload(3000), 3000, 0, false), nil},
		{"show.s01e01.r00", rar4Volume("show.s01e01.mkv", want[100000:200000], len(want), 3, false), nil},
		{"show.s01e01.r01", rar4Volume("show.s01e01.mkv", want[200000:], len(want), 1, false), nil},
		{"Show.S01E01.part02.rar", withRAR4MainFlags(rar4Volume("show.s01e01.mkv", want[:100000], len(want), 2, false), 0x0100), nil},
	})
	selectAndRead(t, files, "show.s01e01.mkv", want)
}

func TestShuffledNumericRAR5NamesUseHeaderOrder(t *testing.T) {
	in, want, _ := regularRARSet(5, 5, 60000, 25000)
	// Volume k is posted as "<hash>.<names[k]>": numbering from 10 that does
	// not follow the volumes, with a non-archive member numbered among them.
	names := []int{13, 10, 14, 11, 12}
	var files []inputFile
	for k, f := range in {
		files = append(files, inputFile{fmt.Sprintf("8c442a49b1caa6c8.%d", names[k]), f.data, nil})
	}
	files = append(files, inputFile{"8c442a49b1caa6c8.15", []byte("release notes"), nil})
	selectAndRead(t, damagedSetup(t, files), "movie.mkv", want)
}

func TestNumericNameAmongObfuscatedFilesIsNotASet(t *testing.T) {
	in, want, _ := regularRARSet(5, 4, 60000, 25000)
	names := []string{"wP1BquZgWa3dA1643727222", "cOMs9bJ7C16437.tprni", "xtAQxs2V3fonJ-ee3r1qt4m0840.444", "UO3ZvR2pt59u.r4ir"}
	var files []inputFile
	for k, f := range in {
		files = append(files, inputFile{names[k], f.data, nil})
	}
	selectAndRead(t, damagedSetup(t, files), "movie.mkv", want)
}

func TestObfuscated7zVolumesSkipInterleavedPAR2(t *testing.T) {
	data := sevenZipFixture(t, "store.7z")
	vols := splitVolumes(data, func(i int) string { return fmt.Sprintf("v%d", i) }, 7000, repeat(16384, 10)...)
	par2 := append([]byte("PAR2\x00PKT"), payload(5000)...)
	stem := "lC9Ehdv593BngIx"
	t.Run("release order", func(t *testing.T) {
		var in []inputFile
		var yenc []string
		for i, v := range vols {
			if i == len(vols)-1 {
				in = append(in, inputFile{"Kq2rVb7Tz0", par2, nil}, inputFile{"Lm8pWc3Xy1", par2, nil})
				yenc = append(yenc, "Kq2rVb7Tz0", "Lm8pWc3Xy1")
			}
			in = append(in, inputFile{fmt.Sprintf("oB%dfu5c", i), v.data, nil})
			yenc = append(yenc, fmt.Sprintf("oB%dfu5c", i))
		}
		selectAndRead(t, setupYEnc(t, in, yenc), "movie.mkv", moviePayload())
	})
	t.Run("yEnc names", func(t *testing.T) {
		// Shuffled release order; the yEnc names still number the volumes.
		var in []inputFile
		var yenc []string
		for _, i := range []int{0, 3, 1, 5, 2, 4} {
			if i >= len(vols) {
				continue
			}
			in = append(in, inputFile{fmt.Sprintf("r4nd%dm", i*7), vols[i].data, nil})
			yenc = append(yenc, fmt.Sprintf("%s.7z.%03d", stem, i+1))
		}
		for i := 6; i < len(vols); i++ {
			in = append(in, inputFile{fmt.Sprintf("r4nd%dm", i*7), vols[i].data, nil})
			yenc = append(yenc, fmt.Sprintf("%s.7z.%03d", stem, i+1))
		}
		in = append(in, inputFile{"Kq2rVb7Tz0", par2, nil})
		yenc = append(yenc, stem+".7z.vol00+01.par2")
		selectAndRead(t, setupYEnc(t, in, yenc), "movie.mkv", moviePayload())
	})
}
