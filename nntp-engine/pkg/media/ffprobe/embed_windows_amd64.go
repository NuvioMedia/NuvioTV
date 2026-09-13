//go:build embedffprobe && windows && amd64

package ffprobe

import _ "embed"

//go:embed bin/ffprobe-windows-amd64.exe
var embeddedFFprobeData []byte

func embeddedFFprobeBinary() (data []byte, name string, ok bool) {
	if len(embeddedFFprobeData) == 0 {
		return nil, "", false
	}
	return embeddedFFprobeData, "ffprobe.exe", true
}
