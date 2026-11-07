package engine

import (
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestFastNZBFetchPreservesSignedURL(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(1024), nil}})
	for _, query := range []string{
		"?id=release&sig=keep%2fthese%20bytes&z=2&a=1",
		"?t=get&id=release&sig=a%2Bb&sig=second",
		"?gzip=0&id=release&sig=unchanged",
	} {
		for _, compressed := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/compressed=%v", query, compressed), func(t *testing.T) {
				requestURI := "/api/download" + query
				var requests atomic.Int32
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					requests.Add(1)
					if r.RequestURI != requestURI {
						t.Errorf("signed request changed: got %q, want %q", r.RequestURI, requestURI)
						http.Error(w, "invalid signature", http.StatusForbidden)
						return
					}
					if r.Header.Get("Accept-Encoding") != "gzip" {
						t.Error("missing compression negotiation")
					}
					if compressed {
						w.Header().Set("Content-Encoding", "gzip")
						gz := gzip.NewWriter(w)
						defer gz.Close()
						io.WriteString(gz, xml)
					} else {
						io.WriteString(w, xml)
					}
				}))
				defer srv.Close()
				files, err := FetchNZB(context.Background(), srv.Client(), srv.URL+requestURI, nil, nil, true)
				if err != nil {
					t.Fatal(err)
				}
				if len(files) != 1 || files[0].Name != "movie.mkv" || requests.Load() != 1 {
					t.Fatalf("unexpected result: files=%d requests=%d", len(files), requests.Load())
				}
			})
		}
	}
}
