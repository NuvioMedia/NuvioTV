package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"streamnzb/pkg/core/logger"
)

func main() {
	logger.Init("INFO")

	port := flag.Int("port", 8191, "loopback HTTP port")
	token := flag.String("token", "", "management API token")
	flag.Parse()
	if *port < 1 || *port > 65535 {
		log.Fatal("invalid port")
	}
	if len(*token) < 32 {
		log.Fatal("management API token is missing or too short")
	}

	listener, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", *port))
	if err != nil {
		log.Fatal("failed to bind loopback server")
	}

	registry := newSessionRegistry(2, 12*time.Hour)
	api := newAPIServer(registry, fmt.Sprintf("http://127.0.0.1:%d", *port), *token)
	server := &http.Server{
		Handler:           api.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	api.server = server

	stopSignals := make(chan os.Signal, 1)
	signal.Notify(stopSignals, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-stopSignals
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()

	log.Printf("Nuvio NNTP engine listening on 127.0.0.1:%d", *port)
	err = server.Serve(listener)
	registry.closeAll()
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal("loopback server failed")
	}
}
