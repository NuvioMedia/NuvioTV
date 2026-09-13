package nntp

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/textproto"
	"testing"
)

func TestIsArticleNotFound(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"typed 430", &textproto.Error{Code: 430, Msg: "No Such Article"}, true},
		{"wrapped typed 430", fmt.Errorf("fetch <x@y>: %w", &textproto.Error{Code: 430, Msg: "no such article"}), true},
		{"typed non-430", &textproto.Error{Code: 480, Msg: "auth required"}, false},
		{"prose no such article", errors.New("provider said: no such article found"), true},
		{"standalone 430 token", errors.New("unexpected response: 430 dmca removed"), true},
		{"430 inside message id", errors.New("fetch <seg430abc@host> failed: timeout"), false},
		{"430 inside byte count", errors.New("read 14430 bytes then EOF"), false},
		{"unrelated", errors.New("connection refused"), false},
	}
	for _, tc := range cases {
		if got := IsArticleNotFound(tc.err); got != tc.want {
			t.Errorf("%s: IsArticleNotFound(%v) = %v, want %v", tc.name, tc.err, got, tc.want)
		}
	}
}

func TestIsBenignDisconnect(t *testing.T) {
	benign := []error{
		context.Canceled,
		net.ErrClosed,
		fmt.Errorf("read tcp 192.168.1.50:1470->81.171.92.219:563: use of closed network connection"),
		fmt.Errorf("wrapped: %w", context.Canceled),
		errors.New("context canceled"),
	}
	for _, err := range benign {
		if !IsBenignDisconnect(err) {
			t.Errorf("expected benign: %v", err)
		}
	}

	notBenign := []error{
		nil,
		errors.New("430 No Such Article"),
		context.DeadlineExceeded,
		errors.New("connection refused"),
		errors.New("EOF"),
	}
	for _, err := range notBenign {
		if IsBenignDisconnect(err) {
			t.Errorf("expected NOT benign: %v", err)
		}
	}
}
