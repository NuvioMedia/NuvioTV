package main

import "testing"

func TestParseProviderURI(t *testing.T) {
	provider, err := parseProviderURI("nntps://user:p%40ss@example.com:563/12")
	if err != nil {
		t.Fatal(err)
	}
	if provider.host != "example.com" || provider.port != 563 || !provider.useTLS {
		t.Fatalf("unexpected endpoint: %#v", provider)
	}
	if provider.username != "user" || provider.password != "p@ss" || provider.connections != 12 {
		t.Fatal("credentials or connection count were not decoded")
	}
}

func TestParseProviderURIRejectsQuery(t *testing.T) {
	if _, err := parseProviderURI("nntp://example.com/1?password=secret"); err == nil {
		t.Fatal("expected query rejection")
	}
}

func TestParseProvidersCapsTotalConnections(t *testing.T) {
	_, err := parseProviders([]string{"nntps://one.example/60", "nntps://two.example/60"})
	if err == nil {
		t.Fatal("expected total connection cap")
	}
}
