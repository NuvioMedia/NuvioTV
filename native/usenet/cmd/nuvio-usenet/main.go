package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
	"time"

	"github.com/NuvioMedia/NuvioTV/native/usenet/internal/engine"
)

// Bootstrap and ownership travel over stdin, never argv, environment or disk.
// EOF means the Android parent died. The child exits even without lifecycle
// callbacks. stdout contains only the one readiness record; errors use stderr.
func main() {
	if err := run(); err != nil {
		os.Stderr.WriteString("Usenet sidecar stopped: " + err.Error() + "\n")
		os.Exit(1)
	}
}
func run() error {
	var init struct {
		Token        string   `json:"token"`
		Certificates []string `json:"certificates"`
		MemoryMiB    int64    `json:"memoryMiB"`
		NZBCacheDir  string   `json:"nzbCacheDir"`
	}
	decoder := json.NewDecoder(io.LimitReader(os.Stdin, 4<<20))
	if err := decoder.Decode(&init); err != nil {
		return err
	}
	if len(init.Token) < 32 {
		return io.ErrUnexpectedEOF
	}
	limit := init.MemoryMiB
	if limit < 64 {
		limit = 128
	}
	if limit > 512 {
		limit = 512
	}
	debug.SetMemoryLimit(limit << 20)
	roots, _ := x509.SystemCertPool()
	if roots == nil {
		roots = x509.NewCertPool()
	}
	for _, pem := range init.Certificates {
		roots.AppendCertsFromPEM([]byte(pem))
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() { io.Copy(io.Discard, os.Stdin); cancel() }()
	transport := &http.Transport{Proxy: http.ProxyFromEnvironment, TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}, ResponseHeaderTimeout: 30 * time.Second, IdleConnTimeout: 60 * time.Second}
	client := &http.Client{Transport: transport, Timeout: 2 * time.Minute}
	app := engine.NewServer(ctx, init.Token, roots, client)
	app.SetNZBCacheDir(init.NZBCacheDir)
	defer app.Close()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	srv := &http.Server{Handler: app, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32 << 10, BaseContext: func(net.Listener) context.Context { return ctx }}
	go func() { <-ctx.Done(); srv.Close() }()
	if err := json.NewEncoder(os.Stdout).Encode(map[string]any{"protocol": 1, "port": ln.Addr().(*net.TCPAddr).Port}); err != nil {
		ln.Close()
		return err
	}
	err = srv.Serve(ln)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}
