package unpack

import (
	"context"
	"strings"
	"testing"
)

func TestNestDepthContext(t *testing.T) {
	ctx := context.Background()
	if depth := NestDepthFromContext(ctx); depth != 0 {
		t.Errorf("NestDepthFromContext(Background) = %d; want 0", depth)
	}

	ctx2 := WithNestDepth(ctx, 2)
	if depth := NestDepthFromContext(ctx2); depth != 2 {
		t.Errorf("NestDepthFromContext(ctx2) = %d; want 2", depth)
	}
}

func TestTryNestedArchiveRejectsMaxDepth(t *testing.T) {
	ctx := WithNestDepth(context.Background(), MaxNestDepth)
	_, err := tryNestedArchive(ctx, []filePart{{name: "inner.rar", volName: "outer.rar", packedSize: 100}}, nil, "", EpisodeTarget{})
	if err == nil || !strings.Contains(err.Error(), "max archive nesting depth") {
		t.Errorf("tryNestedArchive at max depth returned err %v; want max archive nesting depth error", err)
	}
}

func TestTrySevenZipNestedArchiveRejectsMaxDepth(t *testing.T) {
	ctx := WithNestDepth(context.Background(), MaxNestDepth)
	_, _, _, _, err := TrySevenZipNestedArchive(ctx, nil, "", EpisodeTarget{})
	if err == nil || !strings.Contains(err.Error(), "max archive nesting depth") {
		t.Errorf("TrySevenZipNestedArchive at max depth returned err %v; want max archive nesting depth error", err)
	}
}
