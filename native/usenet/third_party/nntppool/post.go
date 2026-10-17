package nntppool

import (
	"errors"
	"fmt"
	"io"
	"mime"
	"slices"
	"sort"
	"strings"
	"time"
)

// PostHeaders holds the structured headers for an NNTP POST command.
type PostHeaders struct {
	From       string   // required: "user@example.com" or "User Name <user@example.com>"
	Subject    string   // required
	Newsgroups []string // required: list of newsgroups
	MessageID  string   // recommended: "<unique@domain>"
	// Date is the article date. If zero, time.Now().UTC() is used at WriteTo time.
	// The Date header is mandatory per RFC 5536 §3.1.1; many servers reject
	// articles without it.
	Date time.Time
	// Extra holds additional headers (e.g., Organization, X-No-Archive).
	// Values are written verbatim except for CR/LF rejection (injection guard).
	Extra map[string][]string
}

// maxHeaderLineLen is the practical RFC 5322 line-length cap (998 octets
// excluding CRLF). We fold at a comfortably lower threshold to stay safe
// across relays.
const maxHeaderLineLen = 900

// validNewsgroupName matches RFC 5536 §3.1.4 newsgroup name syntax.
// First character must be alphanumeric; remainder allows letters, digits,
// and the punctuation characters '.', '+', '_', '-'.
func isValidNewsgroupName(s string) bool {
	if s == "" {
		return false
	}
	for i, r := range s {
		switch {
		case r >= 'a' && r <= 'z':
		case r >= 'A' && r <= 'Z':
		case r >= '0' && r <= '9':
		case (r == '.' || r == '+' || r == '_' || r == '-') && i > 0:
		default:
			return false
		}
	}
	return true
}

// containsCRLF returns true if s contains a CR or LF, which is forbidden in
// header names and values to prevent header injection.
func containsCRLF(s string) bool {
	return strings.ContainsAny(s, "\r\n")
}

// isValidHeaderName checks RFC 5322 §2.2 field-name: printable ASCII except
// colon and whitespace.
func isValidHeaderName(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < 33 || r > 126 || r == ':' {
			return false
		}
	}
	return true
}

// isASCII returns true if s contains only ASCII bytes.
func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] > 127 {
			return false
		}
	}
	return true
}

// encodeHeaderValue applies RFC 2047 B-encoding when the value contains
// non-ASCII bytes; otherwise returns the value unchanged.
func encodeHeaderValue(s string) string {
	if isASCII(s) {
		return s
	}
	return mime.BEncoding.Encode("utf-8", s)
}

// foldHeaderLine folds a single header line ("Name: value") at whitespace
// boundaries so no physical line exceeds maxHeaderLineLen octets, per RFC
// 5322 §2.2.3. Continuation lines are prefixed with CRLF + TAB.
func foldHeaderLine(line string) string {
	if len(line) <= maxHeaderLineLen {
		return line
	}
	var b strings.Builder
	b.Grow(len(line) + len(line)/maxHeaderLineLen*3)
	remaining := line
	for len(remaining) > maxHeaderLineLen {
		// Find a fold point: the last space or tab within the limit.
		cut := strings.LastIndexAny(remaining[:maxHeaderLineLen], " \t")
		if cut <= 0 {
			// No whitespace found within limit; emit as-is to avoid splitting
			// a token. Subsequent relays may reject, but this is preferable to
			// breaking a Message-ID or quoted-printable token.
			b.WriteString(remaining)
			return b.String()
		}
		b.WriteString(remaining[:cut])
		b.WriteString("\r\n\t")
		remaining = strings.TrimLeft(remaining[cut:], " \t")
	}
	b.WriteString(remaining)
	return b.String()
}

// validate returns nil if the headers are well-formed and safe to write.
func (h PostHeaders) validate() error {
	if strings.TrimSpace(h.From) == "" {
		return errors.New("nntp post: From header is required")
	}
	if containsCRLF(h.From) {
		return errors.New("nntp post: From contains CR/LF")
	}
	if strings.TrimSpace(h.Subject) == "" {
		return errors.New("nntp post: Subject header is required")
	}
	if containsCRLF(h.Subject) {
		return errors.New("nntp post: Subject contains CR/LF")
	}
	if len(h.Newsgroups) == 0 {
		return errors.New("nntp post: at least one newsgroup is required")
	}
	if i := slices.IndexFunc(h.Newsgroups, func(g string) bool {
		return !isValidNewsgroupName(strings.TrimSpace(g))
	}); i >= 0 {
		return fmt.Errorf("nntp post: invalid newsgroup name %q", strings.TrimSpace(h.Newsgroups[i]))
	}
	if h.MessageID != "" {
		if containsCRLF(h.MessageID) {
			return errors.New("nntp post: Message-ID contains CR/LF")
		}
		if !strings.HasPrefix(h.MessageID, "<") || !strings.HasSuffix(h.MessageID, ">") ||
			strings.ContainsAny(h.MessageID, " \t") {
			return fmt.Errorf("nntp post: malformed Message-ID %q", h.MessageID)
		}
	}
	for k, vs := range h.Extra {
		if !isValidHeaderName(k) {
			return fmt.Errorf("nntp post: invalid Extra header name %q", k)
		}
		if slices.ContainsFunc(vs, containsCRLF) {
			return fmt.Errorf("nntp post: Extra header %q value contains CR/LF", k)
		}
	}
	return nil
}

// WriteTo formats the headers as RFC 5322 and writes them to w,
// including the blank line separator after headers.
//
// Returns an error if any required field is missing, or if any header value
// contains CR/LF (header-injection guard).
func (h PostHeaders) WriteTo(w io.Writer) (int64, error) {
	if err := h.validate(); err != nil {
		return 0, err
	}

	var total int64
	write := func(name, value string) error {
		line := foldHeaderLine(fmt.Sprintf("%s: %s", name, value))
		n, err := io.WriteString(w, line+"\r\n")
		total += int64(n)
		return err
	}

	if err := write("From", encodeHeaderValue(h.From)); err != nil {
		return total, err
	}
	// Trim and join newsgroups; validation above guarantees names are well-formed.
	groups := make([]string, len(h.Newsgroups))
	for i, g := range h.Newsgroups {
		groups[i] = strings.TrimSpace(g)
	}
	if err := write("Newsgroups", strings.Join(groups, ", ")); err != nil {
		return total, err
	}
	if err := write("Subject", encodeHeaderValue(h.Subject)); err != nil {
		return total, err
	}
	if h.MessageID != "" {
		if err := write("Message-ID", h.MessageID); err != nil {
			return total, err
		}
	}

	// Date header: only emit if the caller did not supply one via Extra.
	if _, ok := h.Extra["Date"]; !ok {
		t := h.Date
		if t.IsZero() {
			t = time.Now().UTC()
		}
		if err := write("Date", t.UTC().Format(time.RFC1123)); err != nil {
			return total, err
		}
	}

	// Write extra headers in sorted order for deterministic output.
	if len(h.Extra) > 0 {
		keys := make([]string, 0, len(h.Extra))
		for k := range h.Extra {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			for _, v := range h.Extra[k] {
				if err := write(k, v); err != nil {
					return total, err
				}
			}
		}
	}

	// Blank line separating headers from body.
	n, err := io.WriteString(w, "\r\n")
	total += int64(n)
	if err != nil {
		return total, err
	}

	return total, nil
}

// PostResult holds the result of a POST command.
type PostResult struct {
	StatusCode int
	Status     string
}
