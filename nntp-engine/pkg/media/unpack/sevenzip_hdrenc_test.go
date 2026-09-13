package unpack

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// testdata/hdrenc-stored.7z: `7z a -m0=Copy -mhe=on -ptest123`, one stored
// inner file tiny.mkv of 5001 bytes (deliberately not a multiple of the AES
// block) whose exact bytes are testdata/hdrenc-stored-payload.bin. The odd
// length is the point: the tail sat in a partial cipher-buffer read that the
// AES stream used to discard, truncating every header-encrypted archive
// whose payload was not read-size aligned.
func loadHdrencFixture(t *testing.T) (*memoryUnpackableFile, []byte) {
	t.Helper()
	archive, err := os.ReadFile(filepath.Join("testdata", "hdrenc-stored.7z"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	payload, err := os.ReadFile(filepath.Join("testdata", "hdrenc-stored-payload.bin"))
	if err != nil {
		t.Fatalf("read payload: %v", err)
	}
	return &memoryUnpackableFile{name: "hdrenc-stored.7z", data: archive}, payload
}

func TestHeaderEncryptedStoredSevenZipStreamsEveryByte(t *testing.T) {
	f, payload := loadHdrencFixture(t)

	bp, err := CreateSevenZipBlueprint(context.Background(), []UnpackableFile{f}, f.Name(), "test123", EpisodeTarget{})
	if err != nil {
		t.Fatalf("CreateSevenZipBlueprint: %v", err)
	}
	if !bp.Encrypted {
		t.Fatal("fixture should carry an AES-encrypted folder")
	}
	if bp.TotalSize != int64(len(payload)) {
		t.Fatalf("blueprint size = %d, want %d", bp.TotalSize, len(payload))
	}

	s, _, size, err := Open7zStreamFromBlueprint(context.Background(), bp, "test123")
	if err != nil {
		t.Fatalf("Open7zStreamFromBlueprint: %v", err)
	}
	defer s.Close()
	if size != int64(len(payload)) {
		t.Fatalf("stream size = %d, want %d", size, len(payload))
	}

	got, err := io.ReadAll(s)
	if err != nil {
		t.Fatalf("read stream: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("decrypted stream differs from payload (got %d bytes, want %d)", len(got), len(payload))
	}
}

func TestHeaderEncryptedStoredSevenZipSeeksIntoTail(t *testing.T) {
	f, payload := loadHdrencFixture(t)
	bp, err := CreateSevenZipBlueprint(context.Background(), []UnpackableFile{f}, f.Name(), "test123", EpisodeTarget{})
	if err != nil {
		t.Fatalf("CreateSevenZipBlueprint: %v", err)
	}

	for _, off := range []int64{1, 15, 16, 17, int64(len(payload)) - 5, int64(len(payload)) - 17} {
		s, _, _, err := Open7zStreamFromBlueprint(context.Background(), bp, "test123")
		if err != nil {
			t.Fatalf("open for seek %d: %v", off, err)
		}
		if _, err := s.Seek(off, io.SeekStart); err != nil {
			t.Fatalf("seek %d: %v", off, err)
		}
		buf := make([]byte, 64)
		n, _ := io.ReadFull(s, buf)
		want := payload[off:]
		if len(want) > n {
			want = want[:n]
		}
		if n == 0 || !bytes.Equal(buf[:n], want) {
			t.Fatalf("seek %d: served bytes differ from payload (%d bytes)", off, n)
		}
		s.Close()
	}
}

func TestHeaderEncryptedSevenZipDiagnosesPasswordProblems(t *testing.T) {
	f, _ := loadHdrencFixture(t)

	_, err := CreateSevenZipBlueprint(context.Background(), []UnpackableFile{f}, f.Name(), "", EpisodeTarget{})
	if err == nil || !strings.Contains(err.Error(), "no password") {
		t.Fatalf("no-password open should name the missing password, got: %v", err)
	}

	_, err = CreateSevenZipBlueprint(context.Background(), []UnpackableFile{f}, f.Name(), "wrong", EpisodeTarget{})
	if err == nil || !strings.Contains(err.Error(), "password may be wrong") {
		t.Fatalf("wrong-password open should say so, got: %v", err)
	}
}
