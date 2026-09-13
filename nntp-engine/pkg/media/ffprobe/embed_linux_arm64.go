//go:build embedffprobe && linux && arm64

package ffprobe

import _ "embed"

//go:embed bin/ffprobe-linux-arm64
var embeddedFFprobeData []byte

func embeddedFFprobeBinary() (data []byte, name string, ok bool) {
	if len(embeddedFFprobeData) == 0 {
		return nil, "", false
	}
	return embeddedFFprobeData, "ffprobe", true
}
