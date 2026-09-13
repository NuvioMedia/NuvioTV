package rardecode

import "io"

type FilePartInfo struct {
	Path              string `json:"path"`
	DataOffset        int64  `json:"dataOffset"`
	PackedSize        int64  `json:"packedSize"`
	UnpackedSize      int64  `json:"unpackedSize"`
	Stored            bool   `json:"stored"`
	Compressed        bool   `json:"compressed"`
	CompressionMethod string `json:"compressionMethod"`
	Encrypted         bool   `json:"encrypted"`
	Salt              []byte `json:"salt,omitempty"`
	AesKey            []byte `json:"aesKey,omitempty"`
	AesIV             []byte `json:"aesIV,omitempty"`
	KdfIterations     int    `json:"kdfIterations,omitempty"`
}

type ArchiveFileInfo struct {
	Name              string         `json:"name"`
	TotalPackedSize   int64          `json:"totalPackedSize"`
	TotalUnpackedSize int64          `json:"totalUnpackedSize"`
	Parts             []FilePartInfo `json:"parts"`
	AnyEncrypted      bool           `json:"anyEncrypted"`
	AllStored         bool           `json:"allStored"`
	Compressed        bool           `json:"compressed"`
	CompressionMethod string         `json:"compressionMethod"`
}

func compressionMethodName(decVer int) string {
	switch decVer {
	case 0:
		return "stored"
	case 1:
		return "rar2.0"
	case 2:
		return "rar2.9"
	case 3:
		return "rar5.0"
	case 4:
		return "rar7.0"
	default:
		return "unknown"
	}
}

func ListArchiveInfo(name string, opts ...Option) ([]ArchiveFileInfo, error) {
	vm, fileBlocks, err := listFileBlocks(name, opts)
	if err != nil {
		return nil, err
	}

	result := make([]ArchiveFileInfo, 0, len(fileBlocks))

	for _, blocks := range fileBlocks {
		blocks.mu.RLock()
		blockList := blocks.blocks
		blocks.mu.RUnlock()

		if len(blockList) == 0 {
			continue
		}

		firstBlock := blockList[0]

		fileInfo := ArchiveFileInfo{
			Name:              firstBlock.Name,
			TotalUnpackedSize: firstBlock.UnPackedSize,
			Parts:             make([]FilePartInfo, 0, len(blockList)),
			AllStored:         true,
			Compressed:        firstBlock.decVer != 0,
			CompressionMethod: compressionMethodName(firstBlock.decVer),
		}

		for _, block := range blockList {

			volumePath := vm.GetVolumePath(block.volnum)

			stored := block.decVer == 0
			compressed := block.decVer != 0
			compressionMethod := compressionMethodName(block.decVer)

			encrypted := block.Encrypted

			partInfo := FilePartInfo{
				Path:              volumePath,
				DataOffset:        block.dataOff,
				PackedSize:        block.PackedSize,
				UnpackedSize:      block.UnPackedSize,
				Stored:            stored,
				Compressed:        compressed,
				CompressionMethod: compressionMethod,
				Encrypted:         encrypted,
			}

			if encrypted && len(block.key) > 0 {
				partInfo.Salt = block.salt
				partInfo.AesKey = block.key
				partInfo.AesIV = block.iv
				partInfo.KdfIterations = block.kdfCount
			}

			fileInfo.Parts = append(fileInfo.Parts, partInfo)
			fileInfo.TotalPackedSize += block.PackedSize

			if !stored {
				fileInfo.AllStored = false
			}
			if encrypted {
				fileInfo.AnyEncrypted = true
			}
		}

		if fileInfo.TotalUnpackedSize > 0 {
			result = append(result, fileInfo)
		}
	}

	return result, nil
}

func ListArchiveInfoParallel(name string, opts ...Option) ([]ArchiveFileInfo, error) {

	optsWithParallel := append([]Option{ParallelRead(true)}, opts...)
	return ListArchiveInfo(name, optsWithParallel...)
}

type ArchiveIterator struct {
	v       volume
	pr      archiveFile
	vm      *volumeManager
	opts    *options
	current *ArchiveFileInfo
	err     error
	closed  bool
}

func NewArchiveIterator(name string, opts ...Option) (*ArchiveIterator, error) {
	options := getOptions(opts)
	if options.openCheck {
		options.skipCheck = false
	}

	v, err := openVolume(name, options)
	if err != nil {
		return nil, err
	}

	pr := newPackedFileReader(v, options)

	return &ArchiveIterator{
		v:    v,
		pr:   pr,
		vm:   v.vm,
		opts: options,
	}, nil
}

func (it *ArchiveIterator) Next() bool {
	if it.closed {
		it.err = io.ErrClosedPipe
		return false
	}

	if it.err != nil {
		return false
	}

	blocks, err := it.pr.nextFile()
	if err != nil {
		if err == io.EOF {

			it.err = nil
			return false
		}
		it.err = err
		return false
	}

	fileInfo, err := it.buildFileInfo(blocks)
	if err != nil {
		it.err = err
		return false
	}

	if fileInfo.TotalUnpackedSize <= 0 {

		return it.Next()
	}

	it.current = fileInfo
	return true
}

func (it *ArchiveIterator) FileInfo() *ArchiveFileInfo {
	return it.current
}

func (it *ArchiveIterator) Err() error {
	return it.err
}

func (it *ArchiveIterator) Close() error {
	if it.closed {
		return nil
	}
	it.closed = true
	it.current = nil
	if closer, ok := it.v.(io.Closer); ok {
		return closer.Close()
	}
	return nil
}

func (it *ArchiveIterator) buildFileInfo(blocks *fileBlockList) (*ArchiveFileInfo, error) {
	blocks.mu.RLock()
	blockList := blocks.blocks
	blocks.mu.RUnlock()

	if len(blockList) == 0 {
		return nil, io.EOF
	}

	firstBlock := blockList[0]

	fileInfo := &ArchiveFileInfo{
		Name:              firstBlock.Name,
		TotalUnpackedSize: firstBlock.UnPackedSize,
		Parts:             make([]FilePartInfo, 0, len(blockList)),
		AllStored:         true,
		Compressed:        firstBlock.decVer != 0,
		CompressionMethod: compressionMethodName(firstBlock.decVer),
	}

	for _, block := range blockList {

		volumePath := it.vm.GetVolumePath(block.volnum)

		stored := block.decVer == 0
		compressed := block.decVer != 0
		compressionMethod := compressionMethodName(block.decVer)

		encrypted := block.Encrypted

		partInfo := FilePartInfo{
			Path:              volumePath,
			DataOffset:        block.dataOff,
			PackedSize:        block.PackedSize,
			UnpackedSize:      block.UnPackedSize,
			Stored:            stored,
			Compressed:        compressed,
			CompressionMethod: compressionMethod,
			Encrypted:         encrypted,
		}

		if encrypted && len(block.key) > 0 {
			partInfo.Salt = block.salt
			partInfo.AesKey = block.key
			partInfo.AesIV = block.iv
			partInfo.KdfIterations = block.kdfCount
		}

		fileInfo.Parts = append(fileInfo.Parts, partInfo)
		fileInfo.TotalPackedSize += block.PackedSize

		if !stored {
			fileInfo.AllStored = false
		}
		if encrypted {
			fileInfo.AnyEncrypted = true
		}
	}

	return fileInfo, nil
}
