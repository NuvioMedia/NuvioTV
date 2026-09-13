package rardecode

import (
	ctx "context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"runtime"
	"sync"
)

var (
	ErrParallelReadFailed = errors.New("rardecode: parallel read failed")
)

type parallelVolumeReader struct {
	vm              *volumeManager
	opt             *options
	maxConcurrent   int
	volumeCount     int
	headersByVolume map[int][]*fileBlockHeader
	mu              sync.RWMutex
}

type volumeWorkerResult struct {
	volnum  int
	headers []*fileBlockHeader
	err     error
}

func newParallelVolumeReader(vm *volumeManager, opt *options) *parallelVolumeReader {
	maxConcurrent := opt.maxConcurrentVolumes
	if maxConcurrent <= 0 {
		maxConcurrent = runtime.NumCPU()
	}
	return &parallelVolumeReader{
		vm:              vm,
		opt:             opt,
		maxConcurrent:   maxConcurrent,
		headersByVolume: make(map[int][]*fileBlockHeader),
	}
}

func (pvr *parallelVolumeReader) discoverVolumeCount() int {

	count := 0
	for {
		if count >= 1000 {
			break
		}
		_, err := pvr.vm.openVolumeFile(count)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				break
			}

			return -1
		}
		count++
	}
	return count
}

func (pvr *parallelVolumeReader) readVolumeHeaders(c ctx.Context, volnum int) ([]*fileBlockHeader, error) {

	v, err := pvr.vm.newVolume(volnum)
	if err != nil {
		return nil, err
	}
	defer v.Close()

	headers := []*fileBlockHeader{}

	for {
		select {
		case <-c.Done():
			return nil, c.Err()
		default:
		}

		h, err := v.readerVolume.nextBlockHeaderOnly()
		if err != nil {
			if err == io.EOF {
				break
			}
			if err == errVolumeOrArchiveEnd {

				break
			}
			if err == ErrMultiVolume {

				break
			}
			return nil, err
		}

		headers = append(headers, h)
	}

	return headers, nil
}

func (pvr *parallelVolumeReader) safeReadVolumeHeaders(c ctx.Context, volnum int) (headers []*fileBlockHeader, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("rardecode: panic reading volume %d: %v", volnum, r)
		}
	}()
	return pvr.readVolumeHeaders(c, volnum)
}

func (pvr *parallelVolumeReader) worker(c ctx.Context, workCh <-chan int, resultCh chan<- volumeWorkerResult, wg *sync.WaitGroup) {
	defer wg.Done()

	for {
		select {
		case <-c.Done():
			return
		case volnum, ok := <-workCh:
			if !ok {
				return
			}

			headers, err := pvr.safeReadVolumeHeaders(c, volnum)
			select {
			case <-c.Done():
				return
			case resultCh <- volumeWorkerResult{volnum: volnum, headers: headers, err: err}:
			}
		}
	}
}

func (pvr *parallelVolumeReader) readAllVolumesParallel() error {

	volumeCount := pvr.discoverVolumeCount()
	if volumeCount <= 0 {
		return ErrParallelReadFailed
	}
	pvr.volumeCount = volumeCount

	if volumeCount == 1 {
		headers, err := pvr.readVolumeHeaders(ctx.Background(), 0)
		if err != nil {
			return err
		}
		pvr.headersByVolume[0] = headers
		return nil
	}

	c, cancel := ctx.WithCancel(ctx.Background())
	defer cancel()

	workCh := make(chan int, volumeCount)
	resultCh := make(chan volumeWorkerResult, volumeCount)

	for i := 0; i < volumeCount; i++ {
		workCh <- i
	}
	close(workCh)

	numWorkers := min(pvr.maxConcurrent, volumeCount)
	var wg sync.WaitGroup
	wg.Add(numWorkers)

	for i := 0; i < numWorkers; i++ {
		go pvr.worker(c, workCh, resultCh, &wg)
	}

	go func() {
		wg.Wait()
		close(resultCh)
	}()

	var firstErr error
	for result := range resultCh {
		if result.err != nil {
			if firstErr == nil {
				firstErr = result.err
				cancel()
			}
			continue
		}

		pvr.mu.Lock()
		pvr.headersByVolume[result.volnum] = result.headers
		pvr.mu.Unlock()
	}

	if firstErr != nil {
		return firstErr
	}

	pvr.mu.RLock()
	defer pvr.mu.RUnlock()
	if len(pvr.headersByVolume) != volumeCount {
		return ErrParallelReadFailed
	}

	return nil
}

func (pvr *parallelVolumeReader) assembleFileBlocks() []*fileBlockList {
	pvr.mu.RLock()
	defer pvr.mu.RUnlock()

	fileMap := make(map[string]*fileBlockList)
	fileOrder := []string{}

	for volnum := 0; volnum < pvr.volumeCount; volnum++ {
		headers := pvr.headersByVolume[volnum]

		for _, h := range headers {
			fileName := h.Name

			if h.first {

				if _, exists := fileMap[fileName]; !exists {
					fileMap[fileName] = newFileBlockList(h)
					fileOrder = append(fileOrder, fileName)
				} else {

					existing := fileMap[fileName].firstBlock()
					if h.Version > existing.Version {
						fileMap[fileName] = newFileBlockList(h)
					}
				}
			} else {

				if blocks, exists := fileMap[fileName]; exists {

					h.blocknum = len(blocks.blocks)
					blocks.addBlock(h)
				}

			}
		}
	}

	result := make([]*fileBlockList, 0, len(fileOrder))
	for _, fileName := range fileOrder {
		result = append(result, fileMap[fileName])
	}

	return result
}

func listFileBlocksParallel(name string, opts []Option) (*volumeManager, []*fileBlockList, error) {
	options := getOptions(opts)
	if options.openCheck {
		options.skipCheck = false
	}

	v, err := openVolume(name, options)
	if err != nil {
		return nil, nil, err
	}
	defer v.Close()

	pvr := newParallelVolumeReader(v.vm, options)

	err = pvr.readAllVolumesParallel()
	if err != nil {
		return nil, nil, err
	}

	fileBlocks := pvr.assembleFileBlocks()

	if options.openCheck {

		vCheck, err := openVolume(name, options)
		if err != nil {
			return nil, nil, err
		}
		defer vCheck.Close()

		pr := newPackedFileReader(vCheck, options)
		for _, blocks := range fileBlocks {
			if blocks.hasFileHash() {
				f, err := pr.newArchiveFile(blocks)
				if err != nil {
					return nil, nil, err
				}
				_, err = io.Copy(io.Discard, f)
				if err != nil {
					return nil, nil, err
				}
			}
		}
	}

	return v.vm, fileBlocks, nil
}
