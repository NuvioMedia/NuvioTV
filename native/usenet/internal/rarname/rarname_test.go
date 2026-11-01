package rarname

import "testing"

func TestVolumeNumberWidthIndependent(t *testing.T) {
	tests := []struct {
		filename string
		scheme   Scheme
		num      int
	}{
		{"X.part01.rar", SchemePart, 1},
		{"X.part09.rar", SchemePart, 9},
		{"X.part010.rar", SchemePart, 10},
		{"X.part0100.rar", SchemePart, 100},
		{"X.part0259.rar", SchemePart, 259},
		{"X.part001.rar", SchemePart, 1},
		{"movie.rar", SchemeRoll, 0},
		{"movie.r00", SchemeRoll, 1},
		{"movie.s00", SchemeRoll, 101},
		{"archive.003", SchemeNumeric, 3},
		{"movie.mkv", SchemeUnknown, 0},
	}
	for _, tt := range tests {
		t.Run(tt.filename, func(t *testing.T) {
			s, n, ok := VolumeNumber(tt.filename)
			wantOK := tt.scheme != SchemeUnknown
			if ok != wantOK || s != tt.scheme || n != tt.num {
				t.Errorf("VolumeNumber(%q) = (%v, %d, %t); want (%v, %d, %t)",
					tt.filename, s, n, ok, tt.scheme, tt.num, wantOK)
			}
		})
	}
}

func TestSetKeyWidthIndependent(t *testing.T) {
	// All volumes of one set share a key regardless of padding width.
	want, ok := SetKey("X.part01.rar")
	if !ok {
		t.Fatal("SetKey(part01) not ok")
	}
	for _, n := range []string{"X.part09.rar", "X.part010.rar", "X.part0259.rar"} {
		got, ok := SetKey(n)
		if !ok || got != want {
			t.Errorf("SetKey(%q) = (%q, %t); want (%q, true)", n, got, ok, want)
		}
	}
}
