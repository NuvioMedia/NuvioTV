package engine

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"io"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// testdata/rar_encrypted.tar.gz holds archives made with RAR 6.23 from
// mkvPayload(40003) (movie.mkv) and mkvPayload(126007) (long.mkv):
//
//	rar a -m0 -ma{4,5} -p"Pässwörd rar"  p{4,5}.rar movie.mkv
//	rar a -m0 -ma{4,5} -hp"Pässwörd rar" hp{4,5}.rar movie.nfo movie.mkv
//	rar a -m0 -ma{4,5} -p"Pässwörd rar"  -v8k vp{4,5}.rar movie.mkv
//	rar a -m0 -ma{4,5} -hp"Pässwörd rar" -v8k vhp{4,5}.rar movie.mkv
//	rar a -m3 -ma{4,5} -p"Pässwörd rar"  c{4,5}.rar movie.mkv
//	rar a -m0 -ma5 -hp"Pässwörd rar" -v1k many.rar long.mkv   (232 volumes)
//	rar a -m0 -ma5 -p"Pässwörd rar"  -v1k manyp.rar long.mkv  (165 volumes)
const rarTestPassword = "Pässwörd rar"

func mkvPayload(n int) []byte {
	b := payload(n)
	copy(b, []byte{0x1a, 0x45, 0xdf, 0xa3})
	return b
}

func encryptedRARFixtures(t testing.TB) map[string][]byte {
	t.Helper()
	f, err := os.Open(filepath.Join("..", "..", "testdata", "rar_encrypted.tar.gz"))
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

// rarSet posts the archive's volumes in name order, as small articles.
func rarSet(fixtures map[string][]byte, prefix string) []inputFile {
	var names []string
	for name := range fixtures {
		if name == prefix+".rar" || strings.HasPrefix(name, prefix+".part") {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	var in []inputFile
	for _, name := range names {
		data := fixtures[name]
		vol := inputFile{name: name, data: data}
		for left := len(data); left > 0; left -= 3000 {
			vol.sizes = append(vol.sizes, min(left, 3000))
		}
		in = append(in, vol)
	}
	return in
}

func TestEncryptedRARStreams(t *testing.T) {
	fixtures := encryptedRARFixtures(t)
	for _, tc := range []struct {
		set, name string
		size      int
		volumes   int
	}{
		{"p4", "movie.mkv", 40003, 1}, {"p5", "movie.mkv", 40003, 1},
		{"hp4", "movie.mkv", 40003, 1}, {"hp5", "movie.mkv", 40003, 1},
		{"vp4", "movie.mkv", 40003, 0}, {"vp5", "movie.mkv", 40003, 0},
		{"vhp4", "movie.mkv", 40003, 0}, {"vhp5", "movie.mkv", 40003, 0},
		{"many", "long.mkv", 126007, 232}, {"manyp", "long.mkv", 126007, 165},
	} {
		t.Run(tc.set, func(t *testing.T) {
			want := mkvPayload(tc.size)
			vols := rarSet(fixtures, tc.set)
			if tc.volumes > 0 && len(vols) != tc.volumes {
				t.Fatalf("%d volumes, want %d", len(vols), tc.volumes)
			}
			files := setupWithPassword(t, vols, rarTestPassword)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != tc.name || c.Size != int64(tc.size) || c.aes == nil {
				t.Fatalf("selected %q (%d bytes, encrypted=%t)", c.Name, c.Size, c.aes != nil)
			}
			rng := rand.New(rand.NewSource(int64(len(tc.set))))
			r := c.Reader(context.Background(), 1)
			defer r.Close()
			for i := 0; i < 120; i++ {
				off := rng.Int63n(c.Size)
				buf := make([]byte, 1+rng.Intn(9000))
				if _, err := r.Seek(off, io.SeekStart); err != nil {
					t.Fatal(err)
				}
				n, err := io.ReadFull(r, buf)
				if err != nil && !(errors.Is(err, io.ErrUnexpectedEOF) && off+int64(n) == c.Size) {
					t.Fatalf("read at %d: %v", off, err)
				}
				if !bytes.Equal(buf[:n], want[off:off+int64(n)]) {
					t.Fatalf("seek read mismatch at %d (+%d)", off, n)
				}
			}
			if strings.HasPrefix(tc.set, "many") {
				// Volume indexes pass 128, where the RAR5 main header grows.
				c.mu.RLock()
				predicted := c.predicted != nil
				c.mu.RUnlock()
				if !predicted {
					t.Fatal("continuation layout was not predicted")
				}
			}
			if got := readAllContent(t, c); !bytes.Equal(got, want) {
				t.Fatalf("sequential read mismatch (%d bytes)", len(got))
			}
		})
	}
}

func TestEncryptedRARRejections(t *testing.T) {
	fixtures := encryptedRARFixtures(t)
	for _, tc := range []struct {
		set, password string
		want          error
	}{
		{"p5", "", ErrRARPasswordMissing}, {"hp5", "", ErrRARPasswordMissing},
		{"p4", "", ErrRARPasswordMissing}, {"hp4", "", ErrRARPasswordMissing},
		{"p5", "wrong", ErrRARWrongPassword}, {"hp5", "wrong", ErrRARWrongPassword},
		{"p4", "wrong", ErrRARWrongPassword}, {"hp4", "wrong", ErrRARWrongPassword},
		{"c4", rarTestPassword, ErrCompressedRAR}, {"c5", rarTestPassword, ErrCompressedRAR},
	} {
		t.Run(tc.set+"/"+tc.password, func(t *testing.T) {
			files := setupWithPassword(t, rarSet(fixtures, tc.set), tc.password)
			if _, err := Select(context.Background(), files, Selection{}); !errors.Is(err, tc.want) {
				t.Fatalf("err = %v, want %v", err, tc.want)
			}
		})
	}
}

func TestRARKeySchedules(t *testing.T) {
	// Independent of the fixtures: RAR5 is PBKDF2-HMAC-SHA256 (RFC 7914
	// vector, 1 iteration), RAR4 keeps the AES key in big-endian words.
	key, _, err := rar5Keys(context.Background(), "passwd", []byte("salt"), 0)
	if err != nil || !bytes.Equal(key[:8], []byte{0x55, 0xac, 0x04, 0x6e, 0x56, 0xe3, 0x08, 0x9f}) {
		t.Fatalf("rar5 key %x err %v", key, err)
	}
	k1, iv1, _ := rar4Keys(context.Background(), "a", []byte("12345678"))
	k2, iv2, _ := rar4Keys(context.Background(), "a", []byte("12345678"))
	if len(k1) != 16 || len(iv1) != 16 || !bytes.Equal(k1, k2) || !bytes.Equal(iv1, iv2) {
		t.Fatal("rar4 key schedule is not deterministic")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err := rar5Keys(ctx, "cancelled", []byte("salt-x"), 20); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled rar5 derivation: %v", err)
	}
	if _, _, err := rar4Keys(ctx, "cancelled", []byte("87654321")); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled rar4 derivation: %v", err)
	}
}
