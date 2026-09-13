//go:build embedffprobe && !((windows && amd64) || (linux && amd64) || (linux && arm64) || (darwin && amd64) || (darwin && arm64))

package ffprobe

// embeddedFFprobeBinary stub for platform/arch combinations we do not ship an
// embedded ffprobe for. Building with `-tags embedffprobe` on such a target
// still compiles; it simply falls back to PATH / the runtime downloader.
func embeddedFFprobeBinary() (data []byte, name string, ok bool) {
	return nil, "", false
}
