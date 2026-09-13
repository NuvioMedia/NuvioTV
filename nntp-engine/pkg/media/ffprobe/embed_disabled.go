//go:build !embedffprobe

package ffprobe

// embeddedFFprobeBinary is the no-op stub used when the binary is NOT compiled
// with `-tags embedffprobe`. Local/dev builds use this so they compile without
// any binary present in bin/, relying on PATH or the runtime downloader instead.
func embeddedFFprobeBinary() (data []byte, name string, ok bool) {
	return nil, "", false
}
