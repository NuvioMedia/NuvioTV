package main

import (
	"context"
	"errors"
	"fmt"
	"sync"
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

// verifyRequiredArchivesExist validates archive volumes before a local stream
// URL is returned. Multi-volume releases need one header article per volume;
// a shared worker budget avoids multiplying each file's deep sample. A direct
// single-file release keeps the deeper start/middle/end sampling. Only a
// definitive 430 rejects the release; transient provider errors remain
// inconclusive and playback is allowed to continue.
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

	type result struct {
		file   *loader.File
		exists bool
		err    error
	}
	limit := 4
	for _, file := range files {
		if file != nil {
			limit = file.StatConcurrency()
			break
		}
	}
	if limit < 1 {
		limit = 1
	}
	if limit > len(files) {
		limit = len(files)
	}

	jobs := make(chan *loader.File)
	results := make(chan result, len(files))
	var workers sync.WaitGroup
	for worker := 0; worker < limit; worker++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for file := range jobs {
				if file == nil {
					results <- result{err: errors.New("nil archive volume")}
					continue
				}
				exists, err := file.StatSegmentAt(statCtx, 0)
				results <- result{file: file, exists: exists, err: err}
			}
		}()
	}
	go func() {
		for _, file := range files {
			jobs <- file
		}
		close(jobs)
		workers.Wait()
		close(results)
	}()

	var firstErr error
	for result := range results {
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
			cancel()
			return false, fmt.Errorf("archive volume %s segment unavailable: %w", name, errFirstSegmentUnavailable)
		}
	}
	if firstErr != nil {
		return false, firstErr
	}
	return true, nil
}
