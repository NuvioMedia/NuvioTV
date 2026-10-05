package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
	"github.com/javi11/sevenzip"
)

func TestNZBSegmentLimitStopsBeforeDecodingWholeFile(t *testing.T) {
	// No closing tags: the parser must stop at the segment budget, before
	// consuming the rest of this file or waiting for its closing element.
	xml := `<nzb><file subject='"movie.mkv"'><segments>` +
		strings.Repeat(`<segment bytes="1" number="1">a</segment>`, 500001)
	_, err := ParseNZB(strings.NewReader(xml), nil)
	if err == nil || !strings.Contains(err.Error(), "segment metadata limit") {
		t.Fatalf("expected early metadata limit, got %v", err)
	}
}

func TestCorruptArticleFailureRetiresSession(t *testing.T) {
	for _, err := range []error{nntppool.ErrCRCMismatch, nntppool.ErrInvalidYEnc, nntppool.ErrResponseTooLarge} {
		if got := permanentStreamFailure(err); got != "invalid-article" {
			t.Fatalf("corruption category=%q", got)
		}
	}
}

func TestCRCFailureReturnsGoneAndDoesNotRefetchOnReopen(t *testing.T) {
	xml, articles := fixture([]inputFile{{"movie.mkv", payload(10000), nil}})
	for id, wire := range articles {
		trailer := bytes.Index(wire, []byte("=yend "))
		if trailer < 0 {
			t.Fatal("missing fixture trailer")
		}
		articles[id] = append(append([]byte(nil), wire[:trailer]...), []byte("=yend size=10000 pcrc32=00000000\r\n")...)
	}
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { provider.Close() })
	pool, err := nntppool.NewClient(context.Background(), []nntppool.Provider{{Host: provider.Addr(), Connections: 1, SkipPing: true}}, nntppool.WithStatProbe(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pool.Close() })
	store := NewStore(context.Background(), pool, 1<<20)
	t.Cleanup(store.Close)
	files, err := ParseNZB(strings.NewReader(xml), store)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	sess := &Session{ctx: ctx, cancel: cancel, pool: pool, store: store, content: &Content{Name: "movie.mkv", Size: 10000, direct: files[0]}, trace: newStartupTrace()}
	app := NewServer(ctx, "secret", nil, &http.Client{})
	app.sessions["test"] = sess
	srv := httptest.NewServer(app)
	t.Cleanup(func() { srv.Close(); app.Close() })
	for i := 0; i < 3; i++ {
		resp, _, err := requestRange(t, srv, "bytes=0-")
		if err != nil || resp.StatusCode != http.StatusGone || resp.Header.Get("X-Usenet-Failure") != "invalid-article" {
			t.Fatalf("corruption response %d: status=%d category=%q err=%v", i, resp.StatusCode, resp.Header.Get("X-Usenet-Failure"), err)
		}
	}
	if calls := provider.Counters().Bodies; calls != 1 {
		t.Fatalf("corrupt article fetched %d times", calls)
	}
}

func TestSevenZipPayloadKeyDerivationCancels(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	if _, err := derive7zKey(ctx, "password", []byte("salt"), 1<<24); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("payload key derivation ignored timeout: %v", err)
	}
}

func hostileSevenZipCount() []byte {
	// Plain directory: Header, FilesInfo, uint64-max file count, End, End.
	header := append([]byte{1, 5, 0xff}, bytes.Repeat([]byte{0xff}, 8)...)
	header = append(header, 0, 0)
	start := make([]byte, 20)
	binary.LittleEndian.PutUint64(start[8:], uint64(len(header)))
	binary.LittleEndian.PutUint32(start[16:], crc32.ChecksumIEEE(header))
	archive := []byte{'7', 'z', 0xbc, 0xaf, 0x27, 0x1c, 0, 4}
	archive = binary.LittleEndian.AppendUint32(archive, crc32.ChecksumIEEE(start))
	archive = append(archive, start...)
	return append(archive, header...)
}

func TestSevenZipRejectsHostileHeaderCountWithoutPanic(t *testing.T) {
	defer func() {
		if p := recover(); p != nil {
			t.Fatalf("archive-controlled count panicked: %v", p)
		}
	}()
	b := hostileSevenZipCount()
	if _, err := sevenzip.NewReader(bytes.NewReader(b), int64(len(b))); err == nil {
		t.Fatal("hostile count accepted")
	}
}

type corruptBodyClient struct{ calls int }

func (c *corruptBodyClient) BodyStream(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	c.calls++
	return nil, nntppool.ErrCRCMismatch
}
func (c *corruptBodyClient) BodyStreamPriority(ctx context.Context, id string, w io.Writer, meta ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	return c.BodyStream(ctx, id, w, meta...)
}

func TestStoreDoesNotReplayPermanentCRCFailure(t *testing.T) {
	c := &corruptBodyClient{}
	s := NewStore(context.Background(), c, 1<<20)
	defer s.Close()
	a := s.acquire("bad@test", false)
	defer s.release(a)
	if _, err := a.metadata(context.Background()); !errors.Is(err, nntppool.ErrCRCMismatch) {
		t.Fatalf("lost checksum error: %v", err)
	}
	if c.calls != 1 {
		t.Fatalf("retried corrupt article %d times", c.calls)
	}
}
