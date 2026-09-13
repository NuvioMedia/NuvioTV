package nntp

import (
	"errors"
	"fmt"
	"net/textproto"
	"testing"
)

func TestIsAuthFailureOnlyForCredentialCodes(t *testing.T) {
	for _, tc := range []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"481 rejected", &textproto.Error{Code: 481, Msg: "Authentication failed"}, true},
		{"482 out of sequence", &textproto.Error{Code: 482, Msg: "Authentication commands issued out of sequence"}, true},
		{"wrapped 481", fmt.Errorf("authenticate: %w", &textproto.Error{Code: 481, Msg: "rejected"}), true},
		// A refused connection says nothing about the account, so it must never
		// park the provider.
		{"connection refused", errors.New("dial tcp: connection refused"), false},
		{"430 missing article", &textproto.Error{Code: 430, Msg: "No such article"}, false},
		// 502 is ambiguous between "denied" and "too many connections", so it
		// is handled by IsConnectionLimit instead.
		{"502", &textproto.Error{Code: 502, Msg: "Too many connections"}, false},
		// Digit-heavy text must not be mistaken for a status code.
		{"481 inside a message id", errors.New("fetch <seg481abc@host> failed"), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := IsAuthFailure(tc.err); got != tc.want {
				t.Fatalf("IsAuthFailure(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}

func TestIsConnectionLimit(t *testing.T) {
	for _, tc := range []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"502", &textproto.Error{Code: 502, Msg: "Too many connections"}, true},
		{"text too many connections", errors.New("provider said too many connections"), true},
		{"481", &textproto.Error{Code: 481, Msg: "Authentication failed"}, false},
		{"unrelated", errors.New("read timeout"), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := IsConnectionLimit(tc.err); got != tc.want {
				t.Fatalf("IsConnectionLimit(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}
