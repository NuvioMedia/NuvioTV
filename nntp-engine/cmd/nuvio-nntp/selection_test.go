package main

import (
	"testing"
)

func TestCompileFilePatternSupportsRegexLiteral(t *testing.T) {
	matcher, err := compileFilePattern("/episode\\.s01e02/i")
	if err != nil {
		t.Fatal(err)
	}
	if !matcher.MatchString("Episode.S01E02.mkv") {
		t.Fatal("regex literal did not match")
	}
}

func TestCompileFilePatternRejectsUnsupportedFlags(t *testing.T) {
	if _, err := compileFilePattern("/video/u"); err == nil {
		t.Fatal("expected unsupported flag error")
	}
}
