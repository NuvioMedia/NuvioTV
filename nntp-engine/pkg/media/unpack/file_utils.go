package unpack

import (
	"strings"

	"streamnzb/pkg/media/fileutil"
)

const (
	ExtRar  = ".rar"
	ExtZip  = ".zip"
	Ext7z   = ".7z"
	ExtIso  = ".iso"
	ExtMkv  = ".mkv"
	ExtMp4  = ".mp4"
	ExtAvi  = ".avi"
	ExtTs   = ".ts"
	ExtVob  = ".vob"
	ExtWmv  = ".wmv"
	ExtFlv  = ".flv"
	ExtWebm = ".webm"
	ExtMov  = ".mov"
	ExtPar2 = ".par2"
	ExtNfo  = ".nfo"
	ExtNzb  = ".nzb"
)

func ExtractFilename(subject string) string {
	return fileutil.ExtractFilename(subject)
}

func IsVideoFile(name string) bool {
	return fileutil.IsVideoFile(name)
}

func IsArchiveFile(name string) bool {
	lower := strings.ToLower(name)
	return strings.HasSuffix(lower, ExtRar) ||
		strings.HasSuffix(lower, ExtZip) ||
		strings.HasSuffix(lower, Ext7z) ||
		strings.HasSuffix(lower, ExtIso) ||
		IsRarPart(lower) ||
		IsSplitArchivePart(lower)
}

// minLargeMediaBytes is the size above which a file with no recognizable
// extension is presumed to be the media: obfuscated releases leave size as the
// only signal, and nothing else in a release comes close to the video's bulk.
const minLargeMediaBytes = 50 * 1024 * 1024

// isArchiveLikeSuffix reports whether a lowercased name carries an archive or
// parity suffix — the guard that keeps size-based media selection from picking
// a nested archive as the media itself.
func isArchiveLikeSuffix(lower string) bool {
	return strings.HasSuffix(lower, ExtRar) ||
		strings.HasSuffix(lower, ExtZip) ||
		strings.HasSuffix(lower, Ext7z) ||
		strings.HasSuffix(lower, ExtPar2) ||
		IsRarPart(lower)
}

func IsSampleFile(name string) bool {
	return strings.Contains(strings.ToLower(name), "sample")
}

func IsRarPart(name string) bool {
	if len(name) < 4 {
		return false
	}
	ext := name[len(name)-4:]
	return ext[0] == '.' && ext[1] == 'r' && isDigit(ext[2]) && isDigit(ext[3])
}

func IsMiddleRarVolume(name string) bool {
	name = strings.ToLower(name)

	if strings.Contains(name, ".part") && strings.HasSuffix(name, ExtRar) {
		if strings.Contains(name, ".part1.rar") ||
			strings.Contains(name, ".part01.rar") ||
			strings.Contains(name, ".part001.rar") {
			return false
		}
		return true
	}

	if len(name) >= 4 && name[len(name)-4:len(name)-2] == ".r" {
		last := name[len(name)-2:]
		if last != "ar" {
			return true
		}
	}

	// Bare numeric split volumes: .001 opens the set, everything after it is a
	// continuation. `.7z.NNN` is the 7z path's business, not a RAR volume.
	if len(name) >= 4 && !strings.Contains(name, ".7z") {
		ext := name[len(name)-4:]
		if ext[0] == '.' && isDigit(ext[1]) && isDigit(ext[2]) && isDigit(ext[3]) {
			return ext != ".001"
		}
	}
	return false
}

func IsSplitArchivePart(name string) bool {
	if len(name) < 4 {
		return false
	}
	ext := strings.ToLower(name[len(name)-4:])

	if ext[0] == '.' && ext[1] == 'z' && isDigit(ext[2]) && isDigit(ext[3]) {
		return true
	}

	if ext[0] == '.' && isDigit(ext[1]) && isDigit(ext[2]) && isDigit(ext[3]) {
		return true
	}
	return false
}

func isDigit(b byte) bool { return b >= '0' && b <= '9' }
