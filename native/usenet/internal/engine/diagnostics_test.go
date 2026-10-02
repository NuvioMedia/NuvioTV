package engine

import (
	"bytes"
	"compress/gzip"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func BenchmarkNZBColdStartupStages(b *testing.B) {
	for _, tc := range []struct {
		name            string
		files, segments int
	}{{"season", 100, 1600}, {"single", 1, 160000}} {
		b.Run(tc.name, func(b *testing.B) {
			var compressed bytes.Buffer
			gz := gzip.NewWriter(&compressed)
			io.WriteString(gz, benchmarkNZBXML(tc.files, tc.segments))
			gz.Close()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(compressed.Bytes()) }))
			defer srv.Close()
			cache := newNZBCache(b.TempDir())
			var parse, index, disk, bind float64
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				store := NewStore(context.Background(), stalledBody{}, 32<<20)
				trace := newStartupTrace()
				_, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, store, true, cache, "", trace)
				if err != nil || trace.nzbCache.Lookup != "miss" {
					b.Fatal("expected cold miss", err)
				}
				parse += trace.durations["nzb_parse_work"]
				index += trace.marks["nzb_index_finished"] - trace.marks["nzb_index_started"]
				disk += trace.durations["nzb_cache_io"]
				bind += trace.marks["nzb_cache_bound"] - trace.marks["nzb_index_finished"]
				key := store.nzb.key
				store.Close()
				cache.remove(key)
			}
			b.ReportMetric(parse/float64(b.N), "parse-ms/op")
			b.ReportMetric(index/float64(b.N), "index-ms/op")
			b.ReportMetric(disk/float64(b.N), "disk-ms/op")
			b.ReportMetric(bind/float64(b.N), "bind-ms/op")
		})
	}
}

func TestNZBStartupStagesColdAndHit(t *testing.T) {
	xml, _ := fixture([]inputFile{{"secret-filename.mkv", payload(1024), nil}})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		time.Sleep(20 * time.Millisecond)
		io.WriteString(w, xml)
	}))
	defer srv.Close()
	cache := newNZBCache(t.TempDir())
	for _, hit := range []bool{false, true} {
		trace := newStartupTrace()
		_, err := fetchNZB(context.Background(), srv.Client(), srv.URL, nil, nil, true, cache, "", trace)
		if err != nil {
			t.Fatal(err)
		}
		snapshot := trace.snapshot()
		marks := snapshot["marksMs"].(map[string]float64)
		durations := snapshot["durationsMs"].(map[string]float64)
		if hit {
			if trace.nzbCache.Lookup != "hit" {
				t.Fatal("miss on repeat")
			}
			if _, ok := marks["nzb_fetch_started"]; ok {
				t.Fatal("hit reported network fetch")
			}
		} else {
			if trace.nzbCache.Write != "saved" {
				t.Fatal("cold index not saved")
			}
			if _, ok := durations["nzb_body_read"]; !ok {
				t.Fatal("body timing missing")
			}
			for _, pair := range [][2]string{{"nzb_cache_lookup_started", "nzb_cache_lookup_finished"}, {"nzb_fetch_started", "nzb_response_headers"}, {"nzb_parse_started", "nzb_parse_finished"}, {"nzb_index_started", "nzb_index_finished"}} {
				start, a := marks[pair[0]]
				end, b := marks[pair[1]]
				if !a || !b || end < start {
					t.Fatal("invalid stage", pair, marks)
				}
			}
			if durations["nzb_cache_io"] > marks["nzb_index_finished"]-marks["nzb_index_started"] {
				t.Fatal("I/O exceeds containing stage")
			}
		}
		for key := range marks {
			if strings.Contains(key, "secret") {
				t.Fatal("private metadata in trace")
			}
		}
	}
}

type delayedNZBReader struct {
	io.Reader
	once bool
}

func (r *delayedNZBReader) Read(p []byte) (int, error) {
	if !r.once {
		r.once = true
		time.Sleep(20 * time.Millisecond)
	}
	return r.Reader.Read(p)
}

func TestNZBBodyWaitIsSeparatedFromParseWork(t *testing.T) {
	xml, _ := fixture([]inputFile{{"movie.mkv", payload(1024), nil}})
	trace := newStartupTrace()
	_, err := parseNZBTraced(&delayedNZBReader{Reader: strings.NewReader(xml)}, nil, trace)
	if err != nil {
		t.Fatal(err)
	}
	if trace.durations["nzb_body_read"] < 15 {
		t.Fatal("blocking read omitted from body timing")
	}
	if total := trace.marks["nzb_parse_finished"] - trace.marks["nzb_parse_started"]; total < trace.durations["nzb_body_read"]+trace.durations["nzb_parse_work"] {
		t.Fatal("parse work double counted body wait")
	}
}
