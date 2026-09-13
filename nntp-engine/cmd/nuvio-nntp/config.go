package main

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

const (
	defaultConnections = 1
	maxConnections     = 100
	maxProviders       = 8
)

type providerEndpoint struct {
	host        string
	port        int
	useTLS      bool
	username    string
	password    string
	connections int
}

func parseProviderURI(value string) (providerEndpoint, error) {
	raw := strings.TrimSpace(value)
	if raw == "" {
		return providerEndpoint{}, fmt.Errorf("blank NNTP server URI")
	}

	parsed, err := url.Parse(raw)
	if err != nil || parsed.Opaque != "" {
		return providerEndpoint{}, fmt.Errorf("invalid NNTP server URI")
	}
	if parsed.RawQuery != "" || parsed.Fragment != "" || parsed.ForceQuery {
		return providerEndpoint{}, fmt.Errorf("NNTP server URI must not contain a query or fragment")
	}

	endpoint := providerEndpoint{connections: defaultConnections}
	switch strings.ToLower(parsed.Scheme) {
	case "nntp":
		endpoint.port = 119
	case "nntps":
		endpoint.port = 563
		endpoint.useTLS = true
	default:
		return providerEndpoint{}, fmt.Errorf("unsupported NNTP scheme")
	}

	endpoint.host = parsed.Hostname()
	if endpoint.host == "" {
		return providerEndpoint{}, fmt.Errorf("NNTP server URI has no host")
	}
	if rawPort := parsed.Port(); rawPort != "" {
		port, parseErr := strconv.Atoi(rawPort)
		if parseErr != nil || port < 1 || port > 65535 {
			return providerEndpoint{}, fmt.Errorf("NNTP server port is out of range")
		}
		endpoint.port = port
	}

	if parsed.User != nil {
		endpoint.username = parsed.User.Username()
		if endpoint.username == "" {
			return providerEndpoint{}, fmt.Errorf("NNTP username is blank")
		}
		endpoint.password, _ = parsed.User.Password()
	}

	path := strings.Trim(parsed.EscapedPath(), "/")
	if path != "" {
		if strings.Contains(path, "/") {
			return providerEndpoint{}, fmt.Errorf("NNTP connection path is invalid")
		}
		connections, parseErr := strconv.Atoi(path)
		if parseErr != nil || connections < 1 || connections > maxConnections {
			return providerEndpoint{}, fmt.Errorf("NNTP connection count must be between 1 and %d", maxConnections)
		}
		endpoint.connections = connections
	}

	return endpoint, nil
}

func parseProviders(values []string) ([]providerEndpoint, error) {
	if len(values) == 0 {
		return nil, fmt.Errorf("at least one NNTP server is required")
	}
	if len(values) > maxProviders {
		return nil, fmt.Errorf("at most %d NNTP servers are supported", maxProviders)
	}

	providers := make([]providerEndpoint, 0, len(values))
	totalConnections := 0
	for _, value := range values {
		provider, err := parseProviderURI(value)
		if err != nil {
			return nil, err
		}
		totalConnections += provider.connections
		if totalConnections > maxConnections {
			return nil, fmt.Errorf("total NNTP connections must not exceed %d", maxConnections)
		}
		providers = append(providers, provider)
	}
	return providers, nil
}
