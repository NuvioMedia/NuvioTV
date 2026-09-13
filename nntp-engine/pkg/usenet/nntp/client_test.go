package nntp

import (
	"net"
	"net/textproto"
	"testing"
	"time"
)

func TestReadGreeting(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name    string
		line    string
		wantErr bool
	}{
		{
			name:    "posting allowed greeting",
			line:    "200 server ready\r\n",
			wantErr: false,
		},
		{
			name:    "no posting greeting",
			line:    "201 usenet.premiumize.me NNRP Service Ready - support@premiumize.me (no posting)\r\n",
			wantErr: false,
		},
		{
			name:    "error greeting",
			line:    "400 temporary failure\r\n",
			wantErr: true,
		},
	}

	for _, tc := range tests {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			serverConn, clientConn := net.Pipe()
			t.Cleanup(func() {
				_ = serverConn.Close()
				_ = clientConn.Close()
			})

			go func() {
				_, _ = serverConn.Write([]byte(tc.line))
			}()

			tp := textproto.NewConn(clientConn)
			err := readGreeting(tp)
			if tc.wantErr && err == nil {
				t.Fatalf("readGreeting() expected error, got nil")
			}
			if !tc.wantErr && err != nil {
				t.Fatalf("readGreeting() unexpected error: %v", err)
			}
		})
	}
}

func TestGetSpeed_ResetsOnLongDurationGap(t *testing.T) {
	pool := NewClientPool("news.example.com", 563, true, "user", "pass", 10)
	t.Cleanup(func() {
		pool.Shutdown()
	})

	// Track 50 MB of reads
	pool.TrackRead(50 * 1024 * 1024)

	// Simulate long duration gap (last check was 10 minutes ago)
	pool.speed.lastCheck = time.Now().Add(-10 * time.Minute)

	speed := pool.GetSpeed()
	if speed != 0 {
		t.Fatalf("GetSpeed() after long gap = %v Mbps, want 0", speed)
	}
}
