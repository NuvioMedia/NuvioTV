package main

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"streamnzb/pkg/media/loader"
	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
)

type preflightFetcher struct {
	missingPrefix string
	err           error
	block         time.Duration
	statCount     atomic.Int64
	active        atomic.Int64
	maxActive     atomic.Int64
}

func (f *preflightFetcher) FetchSegment(context.Context, *nzb.Segment, []string) (pool.SegmentData, error) {
	return pool.SegmentData{}, errors.New("not used")
}

func (f *preflightFetcher) StatSegment(ctx context.Context, messageID string, _ []string) (bool, error) {
	f.statCount.Add(1)
	active := f.active.Add(1)
	defer f.active.Add(-1)
	for {
		current := f.maxActive.Load()
		if active <= current || f.maxActive.CompareAndSwap(current, active) {
			break
		}
	}
	if f.block > 0 {
		select {
		case <-time.After(f.block):
		case <-ctx.Done():
			return false, ctx.Err()
		}
	}
	if f.err != nil {
		return false, f.err
	}
	if f.missingPrefix != "" && len(messageID) >= len(f.missingPrefix) && messageID[:len(f.missingPrefix)] == f.missingPrefix {
		return false, nil
	}
	return true, nil
}

func (f *preflightFetcher) StatConcurrency() int { return 4 }

func preflightFiles(fetcher loader.SegmentFetcher, count int) []*loader.File {
	files := make([]*loader.File, count)
	for index := range files {
		name := fmt.Sprintf("part%02d", index)
		segments := make([]nzb.Segment, 20)
		for segmentIndex := range segments {
			segments[segmentIndex] = nzb.Segment{
				ID:     fmt.Sprintf("%s-segment-%d", name, segmentIndex),
				Number: segmentIndex + 1,
				Bytes:  1024,
			}
		}
		files[index] = loader.NewFile(context.Background(), &nzb.File{
			Subject: name, Groups: []string{"alt.test"}, Segments: segments,
		}, nil, fetcher)
	}
	return files
}

func TestVerifyRequiredArchivesExistAcceptsPresentVolumes(t *testing.T) {
	fetcher := &preflightFetcher{}
	exists, err := verifyRequiredArchivesExist(
		context.Background(),
		preflightFiles(fetcher, 12),
	)
	if !exists || err != nil {
		t.Fatalf("verifyRequiredArchivesExist() = (%v, %v), want (true, nil)", exists, err)
	}
	if got := fetcher.statCount.Load(); got != 12 {
		t.Fatalf("STAT count = %d, want one header probe per volume", got)
	}
}

func TestVerifyRequiredArchivesExistRejectsDefinitive430(t *testing.T) {
	exists, err := verifyRequiredArchivesExist(
		context.Background(),
		preflightFiles(&preflightFetcher{missingPrefix: "part07"}, 12),
	)
	if exists || !errors.Is(err, errFirstSegmentUnavailable) {
		t.Fatalf("verifyRequiredArchivesExist() = (%v, %v), want definitive 430", exists, err)
	}
}

func TestVerifyRequiredArchivesExistKeepsTransientErrorInconclusive(t *testing.T) {
	transient := errors.New("connection reset by peer")
	exists, err := verifyRequiredArchivesExist(
		context.Background(),
		preflightFiles(&preflightFetcher{err: transient}, 12),
	)
	if exists || !errors.Is(err, transient) {
		t.Fatalf("verifyRequiredArchivesExist() = (%v, %v), want transient error", exists, err)
	}
	if errors.Is(err, errFirstSegmentUnavailable) {
		t.Fatal("transient error was classified as a missing article")
	}
}

func TestVerifyRequiredArchivesExistBoundsSingleFileProbe(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	exists, err := verifyRequiredArchivesExist(
		ctx,
		preflightFiles(&preflightFetcher{block: time.Minute}, 1),
	)
	if exists || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("verifyRequiredArchivesExist() = (%v, %v), want deadline", exists, err)
	}
	if errors.Is(err, errFirstSegmentUnavailable) {
		t.Fatal("expired probe was classified as a missing article")
	}
}

func TestStatSampleContextLeavesTimeForCaller(t *testing.T) {
	parent, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ctx, cancelSample := statSampleContext(parent)
	defer cancelSample()
	deadline, ok := ctx.Deadline()
	if !ok || time.Until(deadline) > 2600*time.Millisecond {
		t.Fatalf("sampling deadline = %v, want no more than half the caller budget", deadline)
	}
}

func TestVerifyRequiredArchivesExistSharesConcurrencyAcrossVolumes(t *testing.T) {
	fetcher := &preflightFetcher{block: 10 * time.Millisecond}
	exists, err := verifyRequiredArchivesExist(context.Background(), preflightFiles(fetcher, 20))
	if !exists || err != nil {
		t.Fatalf("verifyRequiredArchivesExist() = (%v, %v), want (true, nil)", exists, err)
	}
	if got := fetcher.maxActive.Load(); got > int64(fetcher.StatConcurrency()) {
		t.Fatalf("maximum concurrent STATs = %d, want <= %d", got, fetcher.StatConcurrency())
	}
}
