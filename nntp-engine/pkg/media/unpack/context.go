package unpack

import (
	"context"
	"errors"
	"fmt"
	"streamnzb/pkg/media/loader"
)

type archiveScanIOTraceContextKey struct{}

const MaxNestDepth = 3

type nestDepthContextKey struct{}

func WithNestDepth(ctx context.Context, depth int) context.Context {
	return context.WithValue(ctx, nestDepthContextKey{}, depth)
}

func NestDepthFromContext(ctx context.Context) int {
	if ctx == nil {
		return 0
	}
	depth, _ := ctx.Value(nestDepthContextKey{}).(int)
	return depth
}

type probeConfigContextKey struct{}

type probeConfig struct {
	ffprobePath string
	quick       bool
}

// WithProbeConfig records which ffprobe binary content probes should use and
// whether they must bound their read window (the serve path, where a probe
// precedes the first byte). It rides the context so nested-archive recursion
// and the direct-candidate probe inherit it for free — the alternative was
// threading two parameters through every scan signature.
func WithProbeConfig(ctx context.Context, ffprobePath string, quick bool) context.Context {
	if ctx == nil {
		ctx = context.Background()
	}
	return context.WithValue(ctx, probeConfigContextKey{}, probeConfig{ffprobePath: ffprobePath, quick: quick})
}

// probeConfigFrom returns the configured ffprobe path and quick-window flag,
// or zero values when the caller never set them (tests, legacy entry points).
func probeConfigFrom(ctx context.Context) (string, bool) {
	if ctx == nil {
		return "", false
	}
	cfg, _ := ctx.Value(probeConfigContextKey{}).(probeConfig)
	return cfg.ffprobePath, cfg.quick
}

func WithArchiveScanIOTrace(ctx context.Context) context.Context {
	return context.WithValue(ctx, archiveScanIOTraceContextKey{}, true)
}

func IsArchiveScanIOTraceEnabled(ctx context.Context) bool {
	if ctx == nil {
		return false
	}
	enabled, _ := ctx.Value(archiveScanIOTraceContextKey{}).(bool)
	return enabled
}

// playbackSegmentMapCtx returns a context for on-demand segment-map detection during
// playback reads/seeks. It skips expensive gap probing that is only needed for
// one-time archive sizing.
func playbackSegmentMapCtx(ctx context.Context) context.Context {
	if ctx == nil {
		ctx = context.Background()
	}
	return loader.WithSkipGapProbing(ctx, true)
}

var ErrArchiveFastProbe = errors.New("archive fast probe incomplete")

func markArchiveFastProbe(err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%w: %v", ErrArchiveFastProbe, err)
}

// definitiveArchiveErrs are structural verdicts about the release itself:
// reproducible from the same bytes on every provider, every retry, forever.
// The fast-probe caveat exists for scans that may simply not have looked hard
// enough, which is not what these say.
var definitiveArchiveErrs = []error{ErrCompressedArchive}

func isDefinitiveArchiveErr(err error) bool {
	for _, sentinel := range definitiveArchiveErrs {
		if errors.Is(err, sentinel) {
			return true
		}
	}
	return false
}

// maybeMarkArchiveFastProbe wraps non-nil errors with ErrArchiveFastProbe.
// Fast failover mode is always enabled, so archive scan errors are marked as
// fast-probe incomplete so callers can avoid reporting them to AvailNZB as
// definitive bad-release signals.
//
// Errors that already carry a definitive sentinel are passed through untouched:
// marking them tells callers to fail open, which leaves a permanently
// unstreamable release unmarked and re-offered on every single search.
func maybeMarkArchiveFastProbe(_ context.Context, err error) error {
	if err == nil {
		return err
	}
	if isDefinitiveArchiveErr(err) {
		return err
	}
	return markArchiveFastProbe(err)
}

type contextAwareSegmentMapEnsurer interface {
	EnsureSegmentMapCtx(ctx context.Context) error
}

func ensureSegmentMap(ctx context.Context, f UnpackableFile) error {
	if err := contextErr(ctx); err != nil {
		return err
	}
	if ensurer, ok := f.(contextAwareSegmentMapEnsurer); ok {
		return ensurer.EnsureSegmentMapCtx(ctx)
	}
	return f.EnsureSegmentMap()
}

func contextErr(ctx context.Context) error {
	if ctx == nil {
		return nil
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return nil
}

func isContextErr(err error) bool {
	return errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded)
}
