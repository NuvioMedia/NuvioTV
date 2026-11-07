package util

import "errors"

// Shared so header codecs and the directory parser report the same category.
var ErrResourceLimit = errors.New("sevenzip: archive metadata exceeds resource limit")
