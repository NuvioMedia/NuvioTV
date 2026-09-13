package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const validNZB = `<?xml version="1.0"?><nzb><file subject="Movie.mkv"><groups><group>alt.binaries.test</group></groups><segments><segment bytes="10" number="1">message-id</segment></segments></file></nzb>`

func TestDownloadAndParseNZB(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		_, _ = writer.Write([]byte(validNZB))
	}))
	defer server.Close()

	document, metrics, err := downloadAndParseNZB(server.URL, server.Client())
	if err != nil {
		t.Fatalf("downloadAndParseNZB: %v", err)
	}
	if metrics.bytes != int64(len(validNZB)) {
		t.Fatalf("bytes = %d, want %d", metrics.bytes, len(validNZB))
	}
	if len(document.Files) != 1 || document.Files[0].Subject != "Movie.mkv" {
		t.Fatalf("unexpected parsed files: %+v", document.Files)
	}
}

func TestDownloadAndParseNZBRejectsEmptyResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {}))
	defer server.Close()

	_, _, err := downloadAndParseNZB(server.URL, server.Client())
	if err == nil || !strings.Contains(err.Error(), "empty") {
		t.Fatalf("error = %v, want empty NZB error", err)
	}
}

func TestDownloadAndParseNZBRejectsOversizedContentLength(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Length", "67108865")
		writer.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	_, _, err := downloadAndParseNZB(server.URL, server.Client())
	if err == nil || !strings.Contains(err.Error(), "64 MiB") {
		t.Fatalf("error = %v, want size limit error", err)
	}
}
