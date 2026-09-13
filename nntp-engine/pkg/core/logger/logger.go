package logger

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"streamnzb/pkg/core/env"
	"streamnzb/pkg/core/paths"
)

var Log *slog.Logger

const redactedValue = "[REDACTED]"
const CurrentLogFileName = "streamnzb.log"

var (
	sensitiveURLUserRe = regexp.MustCompile(`(?i)\b([a-z][a-z0-9+.-]*://)([^/@\s]+)@`)
	sensitiveQueryRe   = regexp.MustCompile(`(?i)([?&](?:api[_-]?key|access[_-]?token|auth[_-]?token|refresh[_-]?token|token|password|passwd|pwd|secret|auth_session)=)([^&#\s;]+)`)
	sensitiveAssignRe  = regexp.MustCompile(`(?i)\b((?:api[_-]?key|access[_-]?token|auth[_-]?token|refresh[_-]?token|token|password|passwd|pwd|secret|auth_session)=)([^\s&#;]+)`)
	sensitiveJSONKVRe  = regexp.MustCompile(`(?i)("(?:api[_-]?key|access[_-]?token|auth[_-]?token|refresh[_-]?token|token|password|passwd|pwd|secret|auth_session)"\s*:\s*")([^"]+)`)
	authorizationKVRe  = regexp.MustCompile(`(?i)\b(authorization[=:]\s*)(bearer\s+|basic\s+)?([^\s,;]+)`)
	hexPathSegmentRe   = regexp.MustCompile(`(/)(?i:[0-9a-f]{64})(/|$|[?#])`)
	verboseNNTPLogging atomic.Bool
)

func sanitizeString(s string) string {
	if s == "" {
		return s
	}
	s = sensitiveURLUserRe.ReplaceAllString(s, `${1}`+redactedValue+`@`)
	s = sensitiveQueryRe.ReplaceAllString(s, `${1}`+redactedValue)
	s = sensitiveAssignRe.ReplaceAllString(s, `${1}`+redactedValue)
	s = sensitiveJSONKVRe.ReplaceAllString(s, `${1}`+redactedValue)
	s = authorizationKVRe.ReplaceAllString(s, `${1}${2}`+redactedValue)
	s = hexPathSegmentRe.ReplaceAllString(s, `${1}`+redactedValue+`${2}`)
	return s
}

func isSensitiveKey(key string) bool {
	if key == "" {
		return false
	}
	var b strings.Builder
	b.Grow(len(key))
	for _, r := range key {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		}
	}
	normalized := strings.ToLower(b.String())
	if normalized == "authorization" || normalized == "authsession" {
		return true
	}
	if normalized == "baseurl" || strings.HasSuffix(normalized, "baseurl") {
		return true
	}
	return strings.Contains(normalized, "apikey") ||
		strings.HasSuffix(normalized, "token") ||
		strings.Contains(normalized, "password") ||
		strings.Contains(normalized, "passwd") ||
		strings.Contains(normalized, "secret")
}

func sanitizeAttr(a slog.Attr) slog.Attr {
	if isSensitiveKey(a.Key) {
		return slog.String(a.Key, redactedValue)
	}
	switch a.Value.Kind() {
	case slog.KindString:
		return slog.String(a.Key, sanitizeString(a.Value.String()))
	case slog.KindAny:
		switch v := a.Value.Any().(type) {
		case string:
			return slog.String(a.Key, sanitizeString(v))
		case error:
			return slog.String(a.Key, sanitizeString(v.Error()))
		case fmt.Stringer:
			return slog.String(a.Key, sanitizeString(v.String()))
		default:
			return a
		}
	case slog.KindGroup:
		group := a.Value.Group()
		sanitized := make([]slog.Attr, 0, len(group))
		for _, child := range group {
			sanitized = append(sanitized, sanitizeAttr(child))
		}
		return slog.Attr{Key: a.Key, Value: slog.GroupValue(sanitized...)}
	default:
		return a
	}
}

func sanitizeAttrs(attrs []slog.Attr) []slog.Attr {
	if len(attrs) == 0 {
		return nil
	}
	sanitized := make([]slog.Attr, 0, len(attrs))
	for _, a := range attrs {
		sanitized = append(sanitized, sanitizeAttr(a))
	}
	return sanitized
}

func sanitizeRecord(r slog.Record) slog.Record {
	sanitized := slog.NewRecord(r.Time, r.Level, sanitizeString(r.Message), r.PC)
	r.Attrs(func(a slog.Attr) bool {
		sanitized.AddAttrs(sanitizeAttr(a))
		return true
	})
	return sanitized
}

func formatLogMessage(r slog.Record, loc *time.Location) string {
	formattedTime := r.Time.In(loc)
	msg := fmt.Sprintf("time=%s level=%s msg=%q", formattedTime.Format("2006-01-02T15:04:05.000-07:00"), r.Level, r.Message)
	r.Attrs(func(a slog.Attr) bool {
		msg += fmt.Sprintf(" %s=%v", a.Key, a.Value)
		return true
	})
	return msg
}

var broadcastCh chan<- string

func SetBroadcast(ch chan<- string) {
	broadcastCh = ch

}

// DebugEnabled reports whether debug-level records are currently emitted.
// Use it to skip work done purely to feed debug logging.
func DebugEnabled() bool {
	return Log != nil && Log.Enabled(context.Background(), slog.LevelDebug)
}

// logLevel is the live level for the active handler. SetLevel stores into it,
// so a config reload changes verbosity without rebuilding the handler chain
// (which would also reopen the log file and drop the broadcast subscribers).
var logLevel = new(slog.LevelVar)

// parseLevel maps a configured level name onto its slog level.
func parseLevel(levelStr string) slog.Level {
	switch strings.ToUpper(levelStr) {
	case "TRACE":
		return slog.LevelDebug - 1
	case "DEBUG":
		return slog.LevelDebug
	case "WARN":
		return slog.LevelWarn
	case "ERROR":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func Init(levelStr string) {
	logLevel.Set(parseLevel(levelStr))

	tzEnv := env.TZ()
	var loc *time.Location
	locationMu.Lock()
	if tzEnv != "" {
		loadedLoc, err := time.LoadLocation(tzEnv)
		if err != nil {

			loc = time.Local
			logLocation = time.Local
		} else {
			loc = loadedLoc
			logLocation = loadedLoc
		}
	} else {

		loc = time.Local
		logLocation = time.Local
	}
	locationMu.Unlock()

	// No log file is opened here: the destination is only settled once the
	// -log-file flag and the config have both been read. Records logged in the
	// meantime are buffered by Handle and flushed by SetLogPath.

	tzLoc := loc
	opts := &slog.HandlerOptions{
		Level: logLevel,
		ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {

			if a.Key == slog.TimeKey {

				t := a.Value.Time().In(tzLoc)
				return slog.String("time", t.Format("2006-01-02T15:04:05.000-07:00"))
			}
			return a
		},
	}

	baseHandler := slog.NewTextHandler(os.Stdout, opts)

	handler := &GlobalBroadcastHandler{
		Handler: baseHandler,
	}

	Log = slog.New(handler)
	slog.SetDefault(Log)

	locationMu.RLock()
	currentLoc := logLocation
	currentTZEnv := tzEnv
	locationMu.RUnlock()
	if currentLoc != nil {
		Log.Info("Logger initialized", "timezone", currentLoc.String(), "tz_env", currentTZEnv)
	}
}

type GlobalBroadcastHandler struct {
	slog.Handler
}

var (
	history     []string
	historyMu   sync.RWMutex
	maxHistory  = 500
	logFile     *os.File
	logFileMu   sync.Mutex
	logLocation *time.Location
	locationMu  sync.RWMutex
)

// pending holds records logged before the log file was opened, so startup lines
// still reach the file once SetLogPath settles where it lives. Guarded by
// logFileMu; capped so a process that never opens a file cannot grow it.
var pending []string

const maxPendingLines = 2000

var (
	logPathMu      sync.RWMutex
	currentLogPath string
)

func (h *GlobalBroadcastHandler) Handle(ctx context.Context, r slog.Record) error {
	r = sanitizeRecord(r)

	locationMu.RLock()
	loc := logLocation
	locationMu.RUnlock()

	if loc == nil {
		loc = time.Local
	}

	msg := formatLogMessage(r, loc)

	historyMu.Lock()
	if len(history) >= maxHistory {
		history = history[1:]
	}
	history = append(history, msg)
	historyMu.Unlock()

	err := h.Handler.Handle(ctx, r)

	logFileMu.Lock()
	if logFile != nil {
		fmt.Fprintln(logFile, msg)
	} else if len(pending) < maxPendingLines {
		pending = append(pending, msg)
	}
	logFileMu.Unlock()

	if broadcastCh != nil {
		select {
		case broadcastCh <- msg:
		default:
		}
	}
	return err
}

func (h *GlobalBroadcastHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &GlobalBroadcastHandler{Handler: h.Handler.WithAttrs(sanitizeAttrs(attrs))}
}

func (h *GlobalBroadcastHandler) WithGroup(name string) slog.Handler {
	return &GlobalBroadcastHandler{Handler: h.Handler.WithGroup(name)}
}

func GetHistory() []string {
	historyMu.RLock()
	defer historyMu.RUnlock()

	cp := make([]string, len(history))
	copy(cp, history)
	return cp
}

// resolveLogPath expands a configured value into the file to write: empty means
// <data dir>/streamnzb.log, a directory (existing, or written with a trailing
// separator) gets streamnzb.log inside it, anything else is the file itself.
func resolveLogPath(configured string) string {
	p := strings.TrimSpace(configured)
	if p == "" {
		return filepath.Join(paths.GetDataDir(), CurrentLogFileName)
	}
	trailingSep := strings.HasSuffix(p, "/") || strings.HasSuffix(p, string(os.PathSeparator))
	clean := filepath.Clean(p)
	if trailingSep {
		return filepath.Join(clean, CurrentLogFileName)
	}
	if fi, err := os.Stat(clean); err == nil && fi.IsDir() {
		return filepath.Join(clean, CurrentLogFileName)
	}
	return clean
}

// SetLogPath points file logging at configured — the -log-file flag or LOG_PATH,
// empty for the data directory — rotating whatever file is already there and
// flushing everything logged before the destination was known. Re-setting the
// same destination is a no-op.
func SetLogPath(configured string) {
	logPathMu.Lock()
	target := resolveLogPath(configured)
	previous := currentLogPath
	if target == previous {
		logPathMu.Unlock()
		return
	}
	if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
		logPathMu.Unlock()
		fmt.Fprintf(os.Stderr, "Failed to create log directory for %s: %v\n", target, err)
		return
	}
	rotateExisting(target)
	file, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		logPathMu.Unlock()
		fmt.Fprintf(os.Stderr, "Failed to open log file %s: %v\n", target, err)
		return
	}
	currentLogPath = target
	logPathMu.Unlock()

	// Swap and flush under one lock so buffered startup lines stay ahead of
	// whatever is being logged concurrently.
	logFileMu.Lock()
	old := logFile
	logFile = file
	for _, line := range pending {
		fmt.Fprintln(file, line)
	}
	pending = nil
	logFileMu.Unlock()

	if old != nil {
		_ = old.Close()
	}
	if previous != "" {
		Info("Log file moved", "from", previous, "to", target)
	}
}

// rotateExisting archives the file at path under its own name plus the modified
// timestamp, e.g. streamnzb.log -> streamnzb-20060102-150405.log.
func rotateExisting(path string) {
	fi, err := os.Stat(path)
	if err != nil || !fi.Mode().IsRegular() {
		return
	}
	dir, stem, ext := rotationParts(path)
	archived := filepath.Join(dir, fmt.Sprintf("%s-%s%s", stem, fi.ModTime().Format("20060102-150405"), ext))
	if err := os.Rename(path, archived); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to rotate log file %s -> %s: %v\n", path, archived, err)
	}
}

// rotationParts splits a log path into the pieces rotation and purging share:
// the directory, the base name without extension, and the extension.
func rotationParts(path string) (dir, stem, ext string) {
	dir = filepath.Dir(path)
	base := filepath.Base(path)
	ext = filepath.Ext(base)
	stem = strings.TrimSuffix(base, ext)
	if stem == "" {
		stem, ext = base, ""
	}
	return dir, stem, ext
}

func GetCurrentLogPath() string {
	logPathMu.RLock()
	current := currentLogPath
	logPathMu.RUnlock()
	if current != "" {
		return current
	}
	return resolveLogPath("")
}

// PurgeOldLogs removes older rotated log files so at most keepCount log files remain
// (the current log file plus up to keepCount-1 archived ones). Only timestamped
// files rotated out of the current log — streamnzb-*.log next to a streamnzb.log —
// are considered for deletion; the current file is never removed. keepCount must
// be at least 1.
func PurgeOldLogs(keepCount int) {
	if keepCount < 1 {
		return
	}
	current := GetCurrentLogPath()
	logDir, stem, ext := rotationParts(current)
	currentName := filepath.Base(current)
	archivePrefix := stem + "-"
	entries, err := os.ReadDir(logDir)
	if err != nil {
		return
	}
	type namedInfo struct {
		name string
		path string
		mod  time.Time
	}
	var archived []namedInfo
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if name == currentName || !strings.HasPrefix(name, archivePrefix) || !strings.HasSuffix(name, ext) {
			continue
		}
		path := filepath.Join(logDir, name)
		info, err := e.Info()
		if err != nil {
			continue
		}
		archived = append(archived, namedInfo{name: name, path: path, mod: info.ModTime()})
	}
	maxArchived := keepCount - 1
	if len(archived) <= maxArchived {
		return
	}
	sort.Slice(archived, func(i, j int) bool { return archived[i].mod.Before(archived[j].mod) })
	for i := 0; i < len(archived)-maxArchived; i++ {
		_ = os.Remove(archived[i].path)
	}
}

// SetLevel changes verbosity in place. It deliberately does NOT re-Init: the
// handler, log file and broadcast subscribers stay as they are.
func SetLevel(levelStr string) {
	logLevel.Set(parseLevel(levelStr))
}

func SetVerboseNNTPLogging(enabled bool) {
	verboseNNTPLogging.Store(enabled)
}

func VerboseNNTPLoggingEnabled() bool {
	return verboseNNTPLogging.Load()
}

func VerboseNNTP(msg string, args ...any) {
	if !VerboseNNTPLoggingEnabled() {
		return
	}
	Debug(msg, args...)
}

func Trace(msg string, args ...any) {
	if Log == nil {
		return
	}
	Log.Log(context.TODO(), slog.LevelDebug-1, msg, args...)
}

func Debug(msg string, args ...any) {
	if Log == nil {
		return
	}
	Log.Debug(msg, args...)
}

func Info(msg string, args ...any) {
	if Log == nil {
		return
	}
	Log.Info(msg, args...)
}

func Warn(msg string, args ...any) {
	if Log == nil {
		return
	}
	Log.Warn(msg, args...)
}

func Error(msg string, args ...any) {
	if Log == nil {
		return
	}
	Log.Error(msg, args...)
}
