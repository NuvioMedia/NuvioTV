package unpack

import (
	"regexp"
	"strconv"
	"strings"
)

var (
	rarPartRegex     = regexp.MustCompile(`(?i)\.part(\d+)\.rar$`)
	rarRnnRegex      = regexp.MustCompile(`(?i)\.r(\d+)$`)
	rarRollRegex     = regexp.MustCompile(`(?i)\.([s-z])(\d{2})$`)
	rarFirstRegex    = regexp.MustCompile(`(?i)\.rar$`)
	sevenZipPartReg  = regexp.MustCompile(`(?i)\.7z\.(\d+)$`)
	sevenZipFirstReg = regexp.MustCompile(`(?i)\.7z$`)
	// numericPartRegex matches bare numeric split extensions (.001, .002, …)
	// — a posting style some groups use for RAR volumes with no .rar anywhere
	// in the name. `.7z.NNN` is excluded at the call sites; it belongs to the
	// 7z path.
	numericPartRegex = regexp.MustCompile(`\.(\d{3})$`)
)

type ArchiveKind string

const (
	KindRAR ArchiveKind = "rar"
	Kind7z  ArchiveKind = "7z"
)

func rollVolumeNumber(letter byte, nn int) int {
	letterLower := strings.ToLower(string(letter))[0]
	return 1 + int(letterLower-'r')*100 + nn
}

func GetRARVolumeNumber(filename string) int {
	name := ExtractFilename(filename)
	if m := rarPartRegex.FindStringSubmatch(name); len(m) > 1 {
		n, _ := strconv.Atoi(m[1])
		return n
	}
	if m := rarRnnRegex.FindStringSubmatch(name); len(m) > 1 {
		n, _ := strconv.Atoi(m[1])
		return n + 1
	}
	if m := rarRollRegex.FindStringSubmatch(name); len(m) > 2 {
		nn, _ := strconv.Atoi(m[2])
		return rollVolumeNumber(m[1][0], nn)
	}
	if rarFirstRegex.MatchString(name) {
		return 0
	}
	if m := numericPartRegex.FindStringSubmatch(name); len(m) > 1 {
		if strings.HasSuffix(strings.ToLower(strings.TrimSuffix(name, m[0])), ".7z") {
			return -1
		}
		n, _ := strconv.Atoi(m[1])
		return n
	}
	return -1
}

func Get7zVolumeNumber(filename string) int {
	name := ExtractFilename(filename)
	if m := sevenZipPartReg.FindStringSubmatch(name); len(m) > 1 {
		n, _ := strconv.Atoi(m[1])
		return n
	}
	if sevenZipFirstReg.MatchString(name) {
		return 0
	}
	return -1
}

type BaseNameResult struct {
	Base string
	Kind ArchiveKind
}

func ArchiveBaseName(filename string) *BaseNameResult {
	name := ExtractFilename(filename)

	if m := rarPartRegex.FindStringIndex(name); m != nil {
		return &BaseNameResult{Base: name[:m[0]], Kind: KindRAR}
	}
	if m := rarRnnRegex.FindStringIndex(name); m != nil {
		return &BaseNameResult{Base: name[:m[0]], Kind: KindRAR}
	}
	if m := rarRollRegex.FindStringIndex(name); m != nil {
		return &BaseNameResult{Base: name[:m[0]], Kind: KindRAR}
	}
	if m := rarFirstRegex.FindStringIndex(name); m != nil {
		return &BaseNameResult{Base: name[:m[0]], Kind: KindRAR}
	}
	if m := sevenZipPartReg.FindStringIndex(name); m != nil {
		return &BaseNameResult{Base: name[:m[0]], Kind: Kind7z}
	}
	if m := sevenZipFirstReg.FindStringIndex(name); m != nil {
		return &BaseNameResult{Base: name[:m[0]], Kind: Kind7z}
	}
	return nil
}
