package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHealthIsLoopbackAPIReady(t *testing.T) {
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	request.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("health status = %d", response.Code)
	}
}

func TestManagementAPIRejectsMissingToken(t *testing.T) {
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("health status without token = %d", response.Code)
	}
}
