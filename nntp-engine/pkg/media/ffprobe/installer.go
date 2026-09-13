package ffprobe

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"streamnzb/pkg/core/logger"
)

// FFprobeDownloadURLFor returns the prebuilt ffprobe zip URL and the binary name
// inside the archive for the given GOOS/GOARCH.
//
// Note: ffbinaries v4.4.1 does not publish a native macOS arm64 build, so
// darwin/arm64 maps to the x86_64 build (runs via Rosetta 2 on Apple Silicon).
func FFprobeDownloadURLFor(goos, goarch string) (url string, targetName string, err error) {
	const base = "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v4.4.1/"
	switch goos {
	case "windows":
		return base + "ffprobe-4.4.1-win-64.zip", "ffprobe.exe", nil
	case "linux":
		if goarch == "arm64" {
			// ffbinaries names the 64-bit ARM build "linux-arm-64" (not "linux-arm64").
			return base + "ffprobe-4.4.1-linux-arm-64.zip", "ffprobe", nil
		}
		return base + "ffprobe-4.4.1-linux-64.zip", "ffprobe", nil
	case "darwin":
		return base + "ffprobe-4.4.1-osx-64.zip", "ffprobe", nil
	default:
		return "", "", fmt.Errorf("unsupported operating system: %s", goos)
	}
}

// FFprobeDownloadURL returns the static binary release URL for the current OS/arch.
func FFprobeDownloadURL() (string, string, error) {
	return FFprobeDownloadURLFor(runtime.GOOS, runtime.GOARCH)
}

// DownloadFFprobeBinary downloads and extracts the ffprobe binary for the given
// GOOS/GOARCH, writing it to destPath (which is created/truncated). Used by the
// build-time fetch tool to populate the embed directory and by the runtime
// downloader.
func DownloadFFprobeBinary(ctx context.Context, goos, goarch, destPath string) error {
	url, targetName, err := FFprobeDownloadURLFor(goos, goarch)
	if err != nil {
		return err
	}
	return downloadAndExtractFFprobe(ctx, url, targetName, destPath)
}

func downloadAndExtractFFprobe(ctx context.Context, url, targetName, destPath string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return fmt.Errorf("create download request: %w", err)
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("download ffprobe archive: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download ffprobe failed with HTTP %d", resp.StatusCode)
	}

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read ffprobe zip response: %w", err)
	}

	zipReader, err := zip.NewReader(bytes.NewReader(bodyBytes), int64(len(bodyBytes)))
	if err != nil {
		return fmt.Errorf("open ffprobe zip archive: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(destPath), 0755); err != nil {
		return fmt.Errorf("create dest dir: %w", err)
	}

	for _, zf := range zipReader.File {
		if !strings.EqualFold(filepath.Base(zf.Name), targetName) {
			continue
		}
		rc, err := zf.Open()
		if err != nil {
			return fmt.Errorf("open zip entry %s: %w", zf.Name, err)
		}
		outFile, err := os.OpenFile(destPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0755)
		if err != nil {
			rc.Close()
			return fmt.Errorf("create ffprobe executable %s: %w", destPath, err)
		}
		_, copyErr := io.Copy(outFile, rc)
		rc.Close()
		closeErr := outFile.Close()
		if copyErr != nil {
			return fmt.Errorf("extract ffprobe binary: %w", copyErr)
		}
		if closeErr != nil {
			return fmt.Errorf("finalize ffprobe binary: %w", closeErr)
		}
		return nil
	}

	return fmt.Errorf("binary %s not found inside downloaded zip archive", targetName)
}

// EnsureFFprobe checks if ffprobe is available. If missing, it automatically downloads and extracts
// a standalone ffprobe executable into the local working directory.
func EnsureFFprobe(ctx context.Context, customPath string) (string, error) {
	if path, ok := FindFFprobeBinary(customPath); ok {
		return path, nil
	}

	_, targetName, err := FFprobeDownloadURL()
	if err != nil {
		return "", err
	}

	destPath := targetName
	if ex, err := os.Executable(); err == nil {
		destPath = filepath.Join(filepath.Dir(ex), targetName)
	}

	logger.Info("ffprobe binary not found on system. Auto-downloading standalone ffprobe binary...")
	if err := DownloadFFprobeBinary(ctx, runtime.GOOS, runtime.GOARCH, destPath); err != nil {
		return "", err
	}

	absPath, _ := filepath.Abs(destPath)
	logger.Info("Successfully installed standalone ffprobe binary", "path", absPath)
	return absPath, nil
}
