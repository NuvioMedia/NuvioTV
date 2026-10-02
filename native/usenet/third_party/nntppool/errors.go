package nntppool

import (
	"errors"
	"fmt"
	"time"
)

// AttemptTimeoutError reports an attempt that expired inside its attempt
// window: either it could not be dispatched to a connection ("dispatch"
// phase — the provider is saturated) or it was sent and no first response
// byte arrived ("response" phase — a hung connection, or a server taking
// longer than the window to answer; slow spool lookups for aged articles
// have been measured at ~7.5s to a 430 on a healthy connection). Typed so
// retry logic can escalate the window on response-phase expiry, and so the
// terminal "all providers exhausted" error names what actually happened
// instead of arriving bare.
type AttemptTimeoutError struct {
	Provider string
	Timeout  time.Duration
	Phase    AttemptPhase
	Cause    error // the transport-level error, when the connection's own read deadline noticed first
}

// AttemptPhase names the point in an attempt's life at which its window
// expired. Typed rather than a bare string so a mistyped phase is a compile
// error instead of silently disabling escalation.
type AttemptPhase string

const (
	// PhaseDispatch: the attempt never reached a connection — the provider is
	// saturated.
	PhaseDispatch AttemptPhase = "dispatch"
	// PhaseResponse: the attempt was sent but no first response byte arrived
	// within the window. The phase escalation widens the window for.
	PhaseResponse AttemptPhase = "response"
)

func (e *AttemptTimeoutError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: attempt expired after %s awaiting %s (%v)", e.Provider, e.Timeout, e.Phase, e.Cause)
	}
	return fmt.Sprintf("%s: attempt expired after %s awaiting %s", e.Provider, e.Timeout, e.Phase)
}

func (e *AttemptTimeoutError) Unwrap() error { return e.Cause }

// Error represents an NNTP protocol-level error with a status code.
type Error struct {
	Code    int
	Message string
}

func (e *Error) Error() string {
	return fmt.Sprintf("nntp: %d %s", e.Code, e.Message)
}

// Is matches by semantic category so that, for example, both 430 ("no such article")
// and 423 ("no article with that number") match ErrArticleNotFound.
func (e *Error) Is(target error) bool {
	var t *Error
	if !errors.As(target, &t) {
		return false
	}
	return errorCategory(e.Code) == errorCategory(t.Code)
}

func errorCategory(code int) int {
	switch code {
	case 423, 430:
		return 430 // article not found
	default:
		return code
	}
}

var (
	ErrArticleNotFound    = &Error{Code: 430, Message: "no such article"}
	ErrPostingNotPermitted = &Error{Code: 440, Message: "posting not permitted"}
	ErrPostingFailed       = &Error{Code: 441, Message: "posting failed"}
	ErrAuthRequired       = &Error{Code: 480, Message: "authentication required"}
	ErrAuthRejected       = &Error{Code: 481, Message: "authentication rejected"}
	ErrServiceUnavailable = &Error{Code: 502, Message: "service unavailable"}
	ErrCRCMismatch        = errors.New("nntp: yEnc CRC mismatch")
	ErrProtocolDesync     = errors.New("nntp: protocol desync: expected status line, got binary data")
	ErrQuotaExceeded      = errors.New("nntp: download quota exceeded")
)

// toError maps an NNTP status code to a sentinel error, or returns nil for success codes.
func toError(code int, status string) error {
	switch {
	case code >= 200 && code < 400:
		return nil
	case code == 423 || code == 430:
		return ErrArticleNotFound
	case code == 440:
		return ErrPostingNotPermitted
	case code == 441:
		return ErrPostingFailed
	case code == 480:
		return ErrAuthRequired
	case code == 481:
		return ErrAuthRejected
	case code == 502:
		return ErrServiceUnavailable
	default:
		return &Error{Code: code, Message: status}
	}
}
