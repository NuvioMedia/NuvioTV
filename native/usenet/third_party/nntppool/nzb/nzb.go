package nzb

import (
	"encoding/xml"
	"fmt"
	"io"
)

type NZB struct {
	Files []File `xml:"file"`
}

type File struct {
	Poster   string    `xml:"poster,attr"`
	Date     string    `xml:"date,attr"`
	Subject  string    `xml:"subject,attr"`
	Groups   []string  `xml:"groups>group"`
	Segments []Segment `xml:"segments>segment"`
}

type Segment struct {
	Bytes     int    `xml:"bytes,attr"`
	Number    int    `xml:"number,attr"`
	MessageID string `xml:",chardata"`
}

func Parse(r io.Reader) (*NZB, error) {
	var n NZB
	if err := xml.NewDecoder(r).Decode(&n); err != nil {
		return nil, fmt.Errorf("nzb parse: %w", err)
	}
	return &n, nil
}

// AllSegments returns a flat list of all segments across all files.
func (n *NZB) AllSegments() []Segment {
	var segs []Segment
	for _, f := range n.Files {
		segs = append(segs, f.Segments...)
	}
	return segs
}
