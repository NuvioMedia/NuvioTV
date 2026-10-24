package rapidyenc

import "errors"

// Meta is the result of parsing the yEnc headers (ybegin, ypart, yend)
type Meta struct {
	Raw        bool // Encode body without the yEnc headers
	FileName   string
	FileSize   int64 // Total size of the file
	PartNumber int64
	TotalParts int64
	Offset     int64 // Offset of the part within the file relative to the start, like io.Seeker or io.WriterAt
	PartSize   int64 // Size of the unencoded data
}

// Begin is the "=ypart begin" value calculated from the Offset
func (m Meta) Begin() int64 {
	return m.Offset + 1
}

// End is the "=ypart end" value calculated from the Offset and PartSize
func (m Meta) End() int64 {
	return m.Offset + m.PartSize
}

type DecodedMeta struct {
	Meta
	Hash uint32 // CRC32 hash of the decoded data
}

var (
	errFileNameEmpty = errors.New("file name is empty")
	errFileSize      = errors.New("file size is less than or equal to zero")
	errPartNumber    = errors.New("part number is less than or equal to zero")
	errTotalParts    = errors.New("total parts is less than part number")
	errOffset        = errors.New("offset is less than zero")
	errPartSize      = errors.New("part size is less than or equal to zero")
)

func (m Meta) validate() error {
	if m.Raw {
		return nil
	}
	if len(m.FileName) == 0 {
		return errFileNameEmpty
	}
	if m.FileSize <= 0 {
		return errFileSize
	}
	if m.PartNumber <= 0 {
		return errPartNumber
	}
	if m.TotalParts < m.PartNumber {
		return errTotalParts
	}
	if m.Offset < 0 {
		return errOffset
	}
	if m.PartSize <= 0 {
		return errPartSize
	}

	return nil
}
