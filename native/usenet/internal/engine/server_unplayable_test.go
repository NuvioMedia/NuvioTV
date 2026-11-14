package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
)

func openSessionStatus(t *testing.T, xml string, articles map[string][]byte) (int, string) {
	t.Helper()
	return openSessionStatusWith(t, xml, articles, Config{AllowPrivateNetwork: true})
}

func openSessionStatusWith(t *testing.T, xml string, articles map[string][]byte, config Config) (int, string) {
	t.Helper()
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	defer provider.Close()
	nzb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
	defer nzb.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app := NewServer(ctx, "secret", nil, nzb.Client())
	defer app.Close()
	srv := httptest.NewServer(app)
	defer srv.Close()
	b, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://" + provider.Addr() + "/4"},
		Config: config})
	r, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
	r.Header.Set("Authorization", "Bearer secret")
	resp, err := srv.Client().Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)
	return resp.StatusCode, resp.Header.Get("X-Usenet-Failure")
}

// A release whose articles were removed fails every open the same way: the app
// is told so, and can stop offering it.
func TestOpenNamesARemovedRelease(t *testing.T) {
	xml, articles := fixture([]inputFile{{name: "Release.2026.1080p.mkv", data: mkvPayload(300000)}})
	for id := range articles {
		delete(articles, id)
	}
	status, failure := openSessionStatus(t, xml, articles)
	if status != http.StatusUnprocessableEntity || failure != "missing-article" {
		t.Fatalf("open = %d, X-Usenet-Failure %q; want 422 missing-article", status, failure)
	}
}

// A release that only lacks its password is not unplayable with other input.
func TestOpenDoesNotNameAMissingPassword(t *testing.T) {
	vols := splitVolumes(sevenZipFixture(t, "aes_hdr.7z"), func(i int) string { return fmt.Sprintf("Release.7z.%03d", i+1) }, 7000, repeat(16384, 10)...)
	xml, articles := fixtureWithPassword(vols, "")
	status, failure := openSessionStatus(t, xml, articles)
	if status != http.StatusUnprocessableEntity || failure != "" {
		t.Fatalf("open = %d, X-Usenet-Failure %q; want 422 without a failure kind", status, failure)
	}
}

// A fake release of zeros, posted plain or in a RAR set, is named at open: the
// app remembers it and moves on instead of waiting for the player to give up.
func TestOpenNamesABlankRelease(t *testing.T) {
	blank := make([]byte, 300000)
	for _, files := range [][]inputFile{
		{{name: "Release.2026.2160p.mkv", data: blank}},
		{{name: "Release.2026.2160p.part01.rar", data: rar4Volume("Release.2026.2160p.mkv", blank[:150000], len(blank), 2, false)},
			{name: "Release.2026.2160p.part02.rar", data: rar4Volume("Release.2026.2160p.mkv", blank[150000:], len(blank), 1, false)}},
	} {
		xml, articles := fixture(files)
		status, failure := openSessionStatus(t, xml, articles)
		if status != http.StatusUnprocessableEntity || failure != "blank-content" {
			t.Fatalf("%s: open = %d, X-Usenet-Failure %q; want 422 blank-content", files[0].name, status, failure)
		}
	}
}

// Leading zeros followed by data, and zeros only filled in for a missing
// article, are not a blank release.
func TestOpenKeepsAReleaseWithSomeZeros(t *testing.T) {
	data := mkvPayload(300000)
	clear(data[:60000])
	xml, articles := fixture([]inputFile{{name: "Release.2026.1080p.mkv", data: data}})
	if status, failure := openSessionStatus(t, xml, articles); status != http.StatusOK || failure != "" {
		t.Fatalf("leading zeros: open = %d, X-Usenet-Failure %q; want 200", status, failure)
	}
	clear(data[:64<<10])
	xml, articles = fixture([]inputFile{{name: "Release.2026.1080p.mkv", data: data, sizes: []int{32 << 10, 32 << 10, 300000 - 64<<10}}})
	delete(articles, "f0-s1@test")
	config := Config{AllowPrivateNetwork: true, HoleFilling: true}
	if status, failure := openSessionStatusWith(t, xml, articles, config); status != http.StatusOK || failure != "" {
		t.Fatalf("filled hole: open = %d, X-Usenet-Failure %q; want 200", status, failure)
	}
}
