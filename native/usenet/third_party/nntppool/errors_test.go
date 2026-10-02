package nntppool

import (
	"errors"
	"fmt"
	"testing"
)

func TestError_Error(t *testing.T) {
	tests := []struct {
		code int
		msg  string
		want string
	}{
		{430, "no such article", "nntp: 430 no such article"},
		{480, "authentication required", "nntp: 480 authentication required"},
		{502, "", "nntp: 502 "},
		{0, "empty", "nntp: 0 empty"},
	}
	for _, tt := range tests {
		e := &Error{Code: tt.code, Message: tt.msg}
		if got := e.Error(); got != tt.want {
			t.Errorf("Error{%d, %q}.Error() = %q, want %q", tt.code, tt.msg, got, tt.want)
		}
	}
}

func TestError_Is(t *testing.T) {
	// 423 and 430 are both "article not found" category
	err423 := &Error{Code: 423, Message: "no article with that number"}
	err430 := &Error{Code: 430, Message: "no such article"}
	err480 := &Error{Code: 480, Message: "auth required"}

	// Same category: 423 matches 430
	if !errors.Is(err423, err430) {
		t.Error("423 should match 430 (same category)")
	}
	if !errors.Is(err430, err423) {
		t.Error("430 should match 423 (same category)")
	}
	if !errors.Is(err430, ErrArticleNotFound) {
		t.Error("430 should match ErrArticleNotFound")
	}
	if !errors.Is(err423, ErrArticleNotFound) {
		t.Error("423 should match ErrArticleNotFound")
	}

	// Cross-category rejection
	if errors.Is(err423, err480) {
		t.Error("423 should NOT match 480")
	}
	if errors.Is(err480, err430) {
		t.Error("480 should NOT match 430")
	}

	// Non-*Error target
	if errors.Is(err423, fmt.Errorf("random")) {
		t.Error("*Error should not match non-*Error")
	}
}

func TestErrorCategory(t *testing.T) {
	tests := []struct {
		code int
		want int
	}{
		{423, 430},
		{430, 430},
		{480, 480},
		{200, 200},
		{0, 0},
	}
	for _, tt := range tests {
		if got := errorCategory(tt.code); got != tt.want {
			t.Errorf("errorCategory(%d) = %d, want %d", tt.code, got, tt.want)
		}
	}
}

func TestToError(t *testing.T) {
	tests := []struct {
		code   int
		status string
		want   error
		isNil  bool
	}{
		{200, "OK", nil, true},
		{201, "posting ok", nil, true},
		{399, "upper bound success", nil, true},
		{423, "no article", ErrArticleNotFound, false},
		{430, "no such article", ErrArticleNotFound, false},
		{480, "auth required", ErrAuthRequired, false},
		{481, "auth rejected", ErrAuthRejected, false},
		{502, "service unavailable", ErrServiceUnavailable, false},
	}
	for _, tt := range tests {
		got := toError(tt.code, tt.status)
		if tt.isNil {
			if got != nil {
				t.Errorf("toError(%d, %q) = %v, want nil", tt.code, tt.status, got)
			}
			continue
		}
		if got == nil {
			t.Errorf("toError(%d, %q) = nil, want %v", tt.code, tt.status, tt.want)
			continue
		}
		if !errors.Is(got, tt.want) {
			t.Errorf("toError(%d, %q) = %v, want errors.Is(%v)", tt.code, tt.status, got, tt.want)
		}
	}

	// Unknown code → generic *Error
	got := toError(599, "weird")
	var e *Error
	if !errors.As(got, &e) {
		t.Fatal("toError(599) should return *Error")
	}
	if e.Code != 599 || e.Message != "weird" {
		t.Errorf("toError(599, weird) = {%d, %q}, want {599, weird}", e.Code, e.Message)
	}
}

func TestGreetingError_Error(t *testing.T) {
	e := &greetingError{StatusCode: 502, Message: "service permanently unavailable"}
	want := "nntp greeting: 502 service permanently unavailable"
	if got := e.Error(); got != want {
		t.Errorf("greetingError.Error() = %q, want %q", got, want)
	}
}

func TestGreetingError_Is(t *testing.T) {
	tests := []struct {
		code int
		want bool
	}{
		{502, true},
		{400, true},
		{200, false},
		{481, false},
	}
	for _, tt := range tests {
		e := &greetingError{StatusCode: tt.code, Message: "test"}
		if got := errors.Is(e, ErrMaxConnections); got != tt.want {
			t.Errorf("greetingError{%d}.Is(ErrMaxConnections) = %v, want %v", tt.code, got, tt.want)
		}
	}

	// Non-ErrMaxConnections target
	e := &greetingError{StatusCode: 502, Message: "test"}
	if errors.Is(e, ErrArticleNotFound) {
		t.Error("greetingError should not match ErrArticleNotFound")
	}
}
