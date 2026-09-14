package engine

import (
	"context"
	"fmt"
	"io"
	"os"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// Opt-in real TCP experiments. Two Go scheduler CPUs approximate scheduling
// pressure, not an ARM CPU. RTT, per-connection rate and total link rate are
// controlled independently. No mock decoder, hot cache or STAT sweep is used.
func TestPerformanceMatrix(t *testing.T) {
	if os.Getenv("NUVIO_BENCHMARK") != "1" {
		t.Skip("set NUVIO_BENCHMARK=1 for the TCP tuning experiment")
	}
	previous := runtime.GOMAXPROCS(2)
	defer runtime.GOMAXPROCS(previous)
	drainOverride := os.Getenv("NUVIO_BENCH_ABORT_DRAIN_BYTES")
	var drainBytes int64
	if drainOverride != "" {
		var err error
		drainBytes, err = strconv.ParseInt(drainOverride, 10, 64)
		if err != nil || drainBytes < 0 {
			t.Fatal("NUVIO_BENCH_ABORT_DRAIN_BYTES must be nonnegative (0 uses the pool default)")
		}
	}
	data := payload(48 << 20)
	sizes := make([]int, 64)
	for i := range sizes {
		sizes[i] = 768 << 10
	}
	xml, articles := fixture([]inputFile{{"benchmark.mkv", data, sizes}})
	t.Log("network,trial,connections,pipeline,ahead,cache_mib,first_ms,cold_seek_ms,mib_per_s,slabs_mib,allocs_per_mib,gcs,pipeline_peak,connections_opened,connections_after_first,connections_after_seek,abort_drain_bytes")
	for _, network := range []struct {
		name           string
		rtt            time.Duration
		perConn, total int64
	}{
		{"100mbit_40ms", 40 * time.Millisecond, 4 << 20, 12500000},
		{"400mbit_80ms", 80 * time.Millisecond, 8 << 20, 50000000},
	} {
		for _, cfg := range []struct{ conn, pipeline, ahead, memory int }{
			{4, 2, 4, 32}, {8, 2, 8, 32}, {16, 2, 8, 32}, {8, 4, 8, 32},
			{16, 4, 16, 64}, {32, 4, 16, 64}, {16, 4, 32, 64}, {32, 4, 32, 128}, {16, 8, 32, 64},
		} {
			if filter := os.Getenv("NUVIO_BENCH_CASE"); filter != "" && filter != fmt.Sprintf("%d/%d/%d", cfg.conn, cfg.pipeline, cfg.ahead) {
				continue
			}
			for trial := 1; trial <= 2; trial++ {
				s, err := nntpserver.New(nntpserver.Config{Articles: articles, RTT: network.rtt, BandwidthPerConn: network.perConn, AggregateBandwidth: network.total})
				if err != nil {
					t.Fatal(err)
				}
				// Use production cancellation/drain policy unless the experiment
				// explicitly overrides it below.
				providers, err := Providers([]string{fmt.Sprintf("nntp://%s/%d", s.Addr(), cfg.conn)}, Config{ReadAhead: cfg.ahead}, nil)
				if err != nil {
					t.Fatal(err)
				}
				providers[0].Inflight = cfg.pipeline
				providers[0].StreamInflight = cfg.pipeline
				if drainOverride != "" {
					providers[0].AbortDrainBytes = drainBytes
				}
				pool, err := nntppool.NewClient(context.Background(), providers, nntppool.WithStatProbe(false))
				if err != nil {
					t.Fatal(err)
				}
				store := NewStore(context.Background(), pool, int64(cfg.memory)<<20)
				files, err := ParseNZB(strings.NewReader(xml), store)
				if err != nil {
					t.Fatal(err)
				}
				buf := make([]byte, 32<<10)
				start := time.Now()
				content, err := Select(context.Background(), files, Selection{})
				if err != nil {
					t.Fatal(err)
				}
				r := content.Reader(context.Background(), cfg.ahead)
				_, err = io.ReadFull(r, buf)
				if err != nil {
					t.Fatal(err)
				}
				first := time.Since(start)
				firstConns := s.Counters().Conns
				r.Seek(int64(len(data)-128<<10), io.SeekStart)
				start = time.Now()
				_, err = io.ReadFull(r, buf)
				if err != nil {
					t.Fatal(err)
				}
				seek := time.Since(start)
				seekConns := s.Counters().Conns
				r.Close()
				var before, after runtime.MemStats
				runtime.ReadMemStats(&before)
				r = content.Reader(context.Background(), cfg.ahead)
				start = time.Now()
				n, err := io.CopyBuffer(io.Discard, r, buf)
				elapsed := time.Since(start)
				r.Close()
				if err != nil || n != int64(len(data)) {
					t.Fatalf("stream failed: %d bytes: %v", n, err)
				}
				runtime.ReadMemStats(&after)
				store.mu.Lock()
				slabs := store.allocated
				store.mu.Unlock()
				if slabs > store.limit || s.Counters().Stats != 0 {
					t.Fatal("memory/probe invariant violated")
				}
				t.Logf("%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.1f,%d,%d,%d,%d,%d,%d", network.name, trial, cfg.conn, cfg.pipeline, cfg.ahead, cfg.memory, float64(first.Microseconds())/1000, float64(seek.Microseconds())/1000, 48/elapsed.Seconds(), float64(slabs)/(1<<20), float64(after.Mallocs-before.Mallocs)/48, after.NumGC-before.NumGC, s.Counters().PeakInflight, s.Counters().Conns, firstConns, seekConns, providers[0].AbortDrainBytes)
				store.Close()
				pool.Close()
				s.Close()
			}
		}
	}
}
