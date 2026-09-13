package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
)

type startupNetwork struct {
	name           string
	rtt            time.Duration
	perConn, total int64
}

var startupNetworks = []startupNetwork{
	{"100mbit_40ms", 40 * time.Millisecond, 4 << 20, 12500000},
	{"400mbit_80ms", 80 * time.Millisecond, 8 << 20, 50000000},
}

func startupMedia(t *testing.T) ([]byte, int64) {
	t.Helper()
	file := os.Getenv("NUVIO_MKV_FIXTURE")
	if file == "" {
		t.Skip("set NUVIO_MKV_FIXTURE to a generated MKV with EOF Cues")
	}
	b, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	cues, ok := mkvCuesOffset(b[:min(len(b), 256<<10)], int64(len(b)))
	if !ok || cues < int64(len(b))/2 {
		t.Fatal("fixture must contain advertised Cues near EOF")
	}
	return b, cues
}

func startupInput(data []byte, archive bool) []inputFile {
	segmentize := func(name string, b []byte) inputFile {
		var sizes []int
		for left := len(b); left > 0; {
			n := min(left, 768<<10)
			sizes = append(sizes, n)
			left -= n
		}
		return inputFile{name, b, sizes}
	}
	if !archive {
		return []inputFile{segmentize("startup.mkv", data)}
	}
	var files []inputFile
	for off, i := 0, 1; off < len(data); i++ {
		end := min(len(data), off+(2<<20))
		var flags uint16
		if off > 0 {
			flags |= 1
		}
		if end < len(data) {
			flags |= 2
		}
		files = append(files, segmentize(fmt.Sprintf("startup.part%02d.rar", i), rar4Volume("startup.mkv", data[off:end], len(data), flags, false)))
		off = end
	}
	return files
}

// Real local TCP/yEnc/HTTP, cold pool and cache for every trial, alternating
// order. This measures an extractor-shaped read sequence, NOT a rendered frame.
func TestMKVStartupMatrix(t *testing.T) {
	if os.Getenv("NUVIO_STARTUP_BENCH") != "1" {
		t.Skip("opt-in startup experiment")
	}
	data, cues := startupMedia(t)
	for _, network := range startupNetworks {
		for _, archive := range []bool{false, true} {
			xml, articles := fixture(startupInput(data, archive))
			nntp, err := nntpserver.New(nntpserver.Config{Articles: articles, RTT: network.rtt, BandwidthPerConn: network.perConn, AggregateBandwidth: network.total, RequireAuth: true})
			if err != nil {
				t.Fatal(err)
			}
			nzb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { time.Sleep(network.rtt); io.WriteString(w, xml) }))
			for trial := 0; trial < 6; trial++ {
				for order := 0; order < 2; order++ {
					fast := (trial+order)%2 == 1
					s := NewServer(context.Background(), "benchmark-token", nil, http.DefaultClient)
					httpServer := httptest.NewServer(s)
					body, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://user:pass@" + nntp.Addr() + "/16"}, Config: Config{FastMKVStartup: fast}})
					req, _ := http.NewRequest("POST", httpServer.URL+"/sessions", bytes.NewReader(body))
					req.Header.Set("Authorization", "Bearer benchmark-token")
					before := nntp.Counters()
					start := time.Now()
					resp, err := http.DefaultClient.Do(req)
					if err != nil {
						t.Fatal(err)
					}
					var result struct{ ID, Path string }
					err = json.NewDecoder(resp.Body).Decode(&result)
					resp.Body.Close()
					if err != nil || resp.StatusCode != 200 {
						t.Fatalf("open: %d %v", resp.StatusCode, err)
					}
					opened := time.Now()
					readRange := func(off int64, n int) {
						t.Helper()
						r, _ := http.NewRequest("GET", httpServer.URL+result.Path, nil)
						// Open-ended ranges followed by early close reproduce the
						// extractor's cancellation, not an orderly short GET.
						r.Header.Set("Range", fmt.Sprintf("bytes=%d-", off))
						res, e := http.DefaultClient.Do(r)
						if e != nil {
							t.Fatal(e)
						}
						buf := make([]byte, n)
						_, e = io.ReadFull(res.Body, buf)
						res.Body.Close()
						if e != nil || !bytes.Equal(buf, data[off:off+int64(n)]) {
							t.Fatalf("range %d: %v", off, e)
						}
					}
					readRange(0, 64<<10)
					head := time.Now()
					readRange(cues, min(32<<10, len(data)-int(cues)))
					index := time.Now()
					readRange(64<<10, 2<<20)
					buffered := time.Now()
					s.mu.Lock()
					session := s.sessions[result.ID]
					s.mu.Unlock()
					after := nntp.Counters()
					ms := func(d time.Duration) float64 { return float64(d.Microseconds()) / 1000 }
					row := map[string]any{"network": network.name, "archive": archive, "trial": trial + 1, "fast": fast, "session_ms": ms(opened.Sub(start)), "head_ms": ms(head.Sub(opened)), "cues_ms": ms(index.Sub(head)), "buffer_ms": ms(buffered.Sub(index)), "total_ms": ms(buffered.Sub(start)), "wire_bytes": after.BytesWritten - before.BytesWritten, "connections": after.Conns - before.Conns, "diagnostics": session.diagnostics()}
					b, _ := json.Marshal(row)
					t.Log("STARTUP_RESULT " + string(b))
					httpServer.Close()
					s.Close()
				}
			}
			nzb.Close()
			nntp.Close()
		}
	}
}

// Keeps controlled providers available to the Android TV emulator. Nothing is
// exposed beyond host loopback. 10.0.2.2 is Android emulator's host-loopback alias.
func TestStartupFixtureServer(t *testing.T) {
	if os.Getenv("NUVIO_STARTUP_SERVER") != "1" {
		t.Skip("opt-in emulator fixture")
	}
	data, cues := startupMedia(t)
	mux := http.NewServeMux()
	var cases []map[string]any
	for _, network := range startupNetworks {
		for _, archive := range []bool{false, true} {
			xml, articles := fixture(startupInput(data, archive))
			nntp, err := nntpserver.New(nntpserver.Config{Articles: articles, RTT: network.rtt, BandwidthPerConn: network.perConn, AggregateBandwidth: network.total, RequireAuth: true})
			if err != nil {
				t.Fatal(err)
			}
			defer nntp.Close()
			name := fmt.Sprintf("%s_rar_%v", network.name, archive)
			mux.HandleFunc("/"+name+".nzb", func(w http.ResponseWriter, r *http.Request) { time.Sleep(network.rtt); io.WriteString(w, xml) })
			cases = append(cases, map[string]any{"name": name, "network": network.name, "archive": archive, "nzbUrl": "http://10.0.2.2:28765/" + name + ".nzb", "provider": "nntp://user:pass@" + strings.Replace(nntp.Addr(), "127.0.0.1", "10.0.2.2", 1) + "/16"})
		}
	}
	mux.HandleFunc("/cases", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"cases": cases, "bytes": len(data), "cues": cues})
	})
	t.Log("emulator fixture ready on 127.0.0.1:28765")
	if err := http.ListenAndServe("127.0.0.1:28765", mux); err != nil {
		t.Fatal(err)
	}
}
