package unpack

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
)

var ErrNo7zFiles = errors.New("no 7z files found")

// Identify7zSplitPartsBySignature finds a 7z posted with bare numeric
// extensions (name.001, name.002, …): nothing in those names says 7z, so the
// lowest-numbered part's first bytes decide. It costs one segment read, so
// callers reach for it only after name-based routing has already failed.
func Identify7zSplitPartsBySignature(ctx context.Context, files []UnpackableFile) ([]UnpackableFile, error) {
	var parts []UnpackableFile
	for _, f := range files {
		name := strings.ToLower(ExtractFilename(f.Name()))
		if !IsSplitArchivePart(name) || strings.Contains(name, ".7z") || IsRarPart(name) {
			continue
		}
		parts = append(parts, f)
	}
	if len(parts) == 0 {
		return nil, ErrNo7zFiles
	}
	// Fixed-width numeric extensions order correctly by name.
	sort.SliceStable(parts, func(i, j int) bool {
		return strings.ToLower(ExtractFilename(parts[i].Name())) < strings.ToLower(ExtractFilename(parts[j].Name()))
	})
	// The first segment needs no segment map; a bare ReadAt(0) would build one
	// first, and on a file context without the skip-gap flag that means probing
	// every article of the volume to answer a six-byte question.
	header, err := readFileHeader(ctx, parts[0])
	if err != nil || len(header) < len(sevenZipMagic) {
		return nil, ErrNo7zFiles
	}
	if !bytes.Equal(header[:len(sevenZipMagic)], sevenZipMagic) {
		return nil, ErrNo7zFiles
	}
	return parts, nil
}

func Identify7zParts(files []UnpackableFile) ([]UnpackableFile, error) {
	var candidates []UnpackableFile

	for _, f := range files {

		name := strings.ToLower(ExtractFilename(f.Name()))

		if !strings.Contains(name, ".7z") {
			continue
		}

		if strings.HasSuffix(name, ".par2") {
			continue
		}

		if strings.HasSuffix(name, ".nzb") || strings.HasSuffix(name, ".nfo") {
			continue
		}

		candidates = append(candidates, f)
	}

	if len(candidates) == 0 {
		return nil, ErrNo7zFiles
	}

	sort.Slice(candidates, func(i, j int) bool {
		nameI := strings.ToLower(ExtractFilename(candidates[i].Name()))
		nameJ := strings.ToLower(ExtractFilename(candidates[j].Name()))
		return nameI < nameJ
	})

	sets := make(map[string][]UnpackableFile)

	for _, f := range candidates {
		name := ExtractFilename(f.Name())
		lower := strings.ToLower(name)

		var key string
		if strings.Contains(lower, ".7z.") {

			idx := strings.Index(lower, ".7z")
			if idx != -1 {
				key = lower[:idx+3]
			} else {
				key = lower
			}
		} else if strings.HasSuffix(lower, ".7z") {

			key = lower
		} else {

			key = lower
		}

		sets[key] = append(sets[key], f)
	}

	var bestSet []UnpackableFile
	var bestSetScore int64

	for _, set := range sets {
		var size int64
		hasOne := false
		for _, f := range set {
			size += f.Size()
			lower := strings.ToLower(ExtractFilename(f.Name()))
			if strings.HasSuffix(lower, ".7z.001") || strings.HasSuffix(lower, ".7z") {
				hasOne = true
			}
		}

		if bestSet == nil || size > bestSetScore {
			bestSetScore = size
			bestSet = set
		} else if size == bestSetScore {

			if hasOne {
				bestSet = set
			}
		}
	}

	if len(bestSet) == 0 {
		return nil, errors.New("no valid 7z sets found")
	}

	sort.Slice(bestSet, func(i, j int) bool {
		return Get7zVolumeNumber(bestSet[i].Name()) < Get7zVolumeNumber(bestSet[j].Name())
	})

	if err := validateSplit7zParts(bestSet); err != nil {
		return nil, err
	}

	return bestSet, nil
}

func validateSplit7zParts(parts []UnpackableFile) error {
	names := make([]string, len(parts))
	for i, f := range parts {
		names[i] = f.Name()
	}
	return validateSplit7zPartNames(names)
}

func validateSplit7zPartNames(partNames []string) error {
	if len(partNames) == 0 {
		return nil
	}

	isSplit := false
	for _, name := range partNames {
		if strings.Contains(strings.ToLower(ExtractFilename(name)), ".7z.") {
			isSplit = true
			break
		}
	}
	if !isSplit {
		return nil
	}

	firstRawSubject := partNames[0]
	first := strings.ToLower(ExtractFilename(firstRawSubject))
	if !strings.HasSuffix(first, ".001") {
		return fmt.Errorf("split 7z archive missing part .001 (first found: %s)", first)
	}

	for i, name := range partNames {
		expectedSuffix := fmt.Sprintf(".%03d", i+1)
		extracted := strings.ToLower(ExtractFilename(name))
		if !strings.HasSuffix(extracted, expectedSuffix) {
			return fmt.Errorf("7z archive sequence error: expected part %s, found %s", expectedSuffix, extracted)
		}
	}

	return nil
}
