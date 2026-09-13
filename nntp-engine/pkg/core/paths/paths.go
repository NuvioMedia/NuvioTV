package paths

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

var (
	overrideMu sync.RWMutex
	override   string
)

// SetDataDir pins the data directory for the whole process. main resolves the
// directory exactly once (honoring the -config flag) and calls this before any
// component asks for it; without the pin, flag-configured runs would put the
// DB, logs and state wherever each caller's own resolution landed.
func SetDataDir(dir string) {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return
	}
	overrideMu.Lock()
	override = filepath.Clean(dir)
	overrideMu.Unlock()
}

func GetDataDir() string {
	overrideMu.RLock()
	pinned := override
	overrideMu.RUnlock()
	if pinned != "" {
		return pinned
	}
	if envPath := strings.TrimSpace(os.Getenv("CONFIG_PATH")); envPath != "" {
		clean := filepath.Clean(envPath)
		if fi, err := os.Stat(clean); err == nil && fi.IsDir() {
			return clean
		}
		return filepath.Dir(clean)
	}
	if _, err := os.Stat("/.dockerenv"); err == nil {
		return "/app/data"
	}
	if _, err := os.Stat("config.json"); err == nil {
		return "."
	}
	if runtime.GOOS == "windows" {
		if local := os.Getenv("LOCALAPPDATA"); local != "" {
			return filepath.Join(local, "streamnzb")
		}
	}
	return "."
}
