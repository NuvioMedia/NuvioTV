package engine

import (
	"bytes"
	"context"
	"fmt"
	"strconv"
	"strings"
	"sync"
)

// An obfuscated 7z set needs every volume's yEnc name only to order its volumes
// and to drop PAR2 files. A large set whose NZB subjects already number every
// file ([1/481] to [481/481]) is posted in volume order, so reading each first
// article before playback (481 whole articles, about 355 MB, for one 117 GiB
// release) buys nothing. Such a set is taken in subject order when:
//   - it has at least subjectOrderMinVolumes files, numbered without a gap;
//   - its first volume's yEnc name numbers it <stem>.7z.001;
//   - every file but the last has as many articles as the first, and the last
//     no more (7z -v writes equal volumes and a shorter last one);
//   - a spread sample of volumes carries the yEnc names their positions
//     predict, as does any volume whose name is already known.
//
// Every other volume is checked against its predicted name when it is first
// read, as its predicted size already is (verifyVolume). A volume that
// disagrees fails the read without retiring the release, and its set is
// remembered so the next open reads every name first, as before.
const (
	subjectOrderMinVolumes = 16
	subjectOrderSamples    = 16
)

// subjectOrderedVolume is the yEnc name a volume's subject position predicts.
type subjectOrderedVolume struct {
	name string
	set  string // remembers the whole set when this volume disagrees.
}

var unorderedSets = struct {
	sync.Mutex
	keys map[string]struct{}
}{keys: map[string]struct{}{}}

func rememberUnorderedSet(key string) {
	unorderedSets.Lock()
	defer unorderedSets.Unlock()
	if len(unorderedSets.keys) >= 256 {
		clear(unorderedSets.keys)
	}
	unorderedSets.keys[key] = struct{}{}
}

func unorderedSet(key string) bool {
	unorderedSets.Lock()
	defer unorderedSets.Unlock()
	_, ok := unorderedSets.keys[key]
	return ok
}

func recoveredName(f *File) string {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.recoveredName
}

// articleCount reads an indexed file's count from its record, so its segment
// list is not loaded for it.
func articleCount(f *File) int {
	if f.cached != nil {
		return f.cached.record.Count
	}
	f.mu.RLock()
	defer f.mu.RUnlock()
	return len(f.segments)
}

// subjectOrdered7zVolumes returns files unchanged, each expecting its predicted
// name, when the conditions above hold. files start at the set's first volume,
// whose first article the caller has read.
func subjectOrdered7zVolumes(ctx context.Context, files []*File) ([]*File, bool) {
	n := len(files)
	if n < subjectOrderMinVolumes {
		return nil, false
	}
	m := sevenZipVolumeName.FindStringSubmatch(recoveredName(files[0]))
	if m == nil {
		return nil, false
	}
	stem, digits := m[1], len(m[2])
	if number, err := strconv.Atoi(m[2]); err != nil || number != 1 {
		return nil, false
	}
	key := fmt.Sprintf("%s/%d", strings.ToLower(stem), n)
	if unorderedSet(key) {
		return nil, false
	}
	articles := articleCount(files[0])
	for k, f := range files {
		f.mu.RLock()
		damaged := f.damaged != nil
		f.mu.RUnlock()
		count := articleCount(f)
		if damaged || f.order <= 0 || f.order != files[0].order+k || count <= 0 || count > articles ||
			(k < n-1 && count != articles) {
			return nil, false
		}
	}
	want := func(k int) string { return fmt.Sprintf("%s.7z.%0*d", stem, digits, k+1) }
	for k, f := range files {
		if name := recoveredName(f); name != "" && !strings.EqualFold(name, want(k)) {
			return nil, false
		}
	}
	sample := map[int]bool{1: true, n - 2: true, n - 1: true}
	for k := 1; k < subjectOrderSamples; k++ {
		sample[k*(n-1)/subjectOrderSamples] = true
	}
	delete(sample, 0) // Read by the caller.
	heads := make(map[int][]byte, len(sample))
	errs := make(map[int]error, len(sample))
	var mu sync.Mutex
	var wg sync.WaitGroup
	limit := make(chan struct{}, 8)
	for k := range sample {
		wg.Add(1)
		limit <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-limit }()
			head, err := sniff(ctx, files[k])
			mu.Lock()
			heads[k], errs[k] = head, err
			mu.Unlock()
		}()
	}
	wg.Wait()
	for k := range sample {
		if errs[k] != nil || bytes.HasPrefix(heads[k], []byte("PAR2\x00PKT")) ||
			!strings.EqualFold(recoveredName(files[k]), want(k)) {
			return nil, false
		}
	}
	for k, f := range files {
		f.mu.Lock()
		f.expected = &subjectOrderedVolume{name: want(k), set: key}
		f.mu.Unlock()
	}
	return files, true
}

// volumeOrderError reports a volume taken in subject order whose yEnc name,
// once read, is not the predicted one, and remembers its set.
func volumeOrderError(f *File) error {
	f.mu.RLock()
	want, got := f.expected, f.recoveredName
	f.mu.RUnlock()
	if want == nil || strings.EqualFold(got, want.name) {
		return nil
	}
	rememberUnorderedSet(want.set)
	return err7zVolumeOrder
}

// forgetSubjectOrder drops the predictions before the full read orders the set.
func forgetSubjectOrder(files []*File) {
	for _, f := range files {
		f.mu.Lock()
		f.expected = nil
		f.mu.Unlock()
	}
}
