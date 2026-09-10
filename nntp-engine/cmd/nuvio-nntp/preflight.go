package main

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"time"

	"streamnzb/pkg/media/loader"
)

var errFirstSegmentUnavailable = errors.New("first segment not found (430)")

type errNZBIncomplete struct{ message string }

func (e *errNZBIncomplete) Error() string { return e.message }

func (e *errNZBIncomplete) Is(target error) bool {
	return target == errFirstSegmentUnavailable
}

const statSampleBudget = 10 * time.Second

func statSampleContext(ctx context.Context) (context.Context, context.CancelFunc) {
	budget := statSampleBudget
	if deadline, ok := ctx.Deadline(); ok {
		if half := time.Until(deadline) / 2; half < budget {
			budget = half
		}
	}
	return context.WithTimeout(ctx, budget)
}

// verifyRequiredArchivesExist samples archive volumes before a local stream URL
// is returned. Only a definitive 430 rejects the release; transient provider
// errors remain inconclusive and playback is allowed to continue.
func verifyRequiredArchivesExist(ctx context.Context, files []*loader.File) (bool, error) {
	if len(files) == 0 {
		return false, errors.New("no files in release")
	}
	for _, file := range files {
		if file == nil {
			continue
		}
		if missing := file.MissingFromNZB(); missing > loader.MaxZeroFills {
			return false, &errNZBIncomplete{message: fmt.Sprintf(
				"archive volume %s is missing %d articles from the NZB itself", file.Name(), missing)}
		}
		if run := file.MissingRunFromNZB(); run > loader.MaxZeroFillRun {
			return false, &errNZBIncomplete{message: fmt.Sprintf(
				"archive volume %s is missing a run of %d consecutive articles from the NZB itself", file.Name(), run)}
		}
	}
	statCtx, cancel := statSampleContext(ctx)
	defer cancel()
	if len(files) == 1 {
		exists, err := files[0].CheckFirstSegmentExists(statCtx)
		if err == nil && !exists {
			return false, fmt.Errorf("archive volume %s segment unavailable: %w", files[0].Name(), errFirstSegmentUnavailable)
		}
		return exists, err
	}

	n := len(files)
	sampleIndices := map[int]bool{0: true, n - 1: true}
	samples := n / 8
	if samples < 11 {
		samples = 11
	}
	if samples > 24 {
		samples = 24
	}
	if samples > n {
		samples = n
	}
	step := float64(n-1) / float64(samples-1)
	for index := 0; index < samples; index++ {
		sampleIndices[int(float64(index)*step)] = true
	}

	indices := make([]int, 0, len(sampleIndices))
	for index := range sampleIndices {
		indices = append(indices, index)
	}
	sort.Ints(indices)

	type result struct {
		file   *loader.File
		exists bool
		err    error
	}
	results := make(chan result, len(indices))
	for _, index := range indices {
		file := files[index]
		go func() {
			if file == nil {
				results <- result{file: file, err: errors.New("nil archive volume")}
				return
			}
			exists, err := file.CheckFirstSegmentExists(statCtx)
			results <- result{file: file, exists: exists, err: err}
		}()
	}

	var firstErr error
	for range indices {
		result := <-results
		if result.err != nil {
			if firstErr == nil {
				firstErr = result.err
			}
			continue
		}
		if !result.exists {
			name := "unknown"
			if result.file != nil {
				name = result.file.Name()
			}
			return false, fmt.Errorf("archive volume %s segment unavailable: %w", name, errFirstSegmentUnavailable)
		}
	}
	if firstErr != nil {
		return false, firstErr
	}
	return true, nil
}
