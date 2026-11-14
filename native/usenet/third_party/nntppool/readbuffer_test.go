package nntppool

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestReadBuffer_Init(t *testing.T) {
	var rb readBuffer
	rb.init()
	if len(rb.buf) != defaultReadBufSize {
		t.Errorf("init() buf size = %d, want %d", len(rb.buf), defaultReadBufSize)
	}

	// Second init is no-op
	rb.init()
	if len(rb.buf) != defaultReadBufSize {
		t.Error("second init() should be no-op")
	}
}

func TestReadBuffer_Window(t *testing.T) {
	rb := readBuffer{
		buf:   make([]byte, 100),
		start: 10,
		end:   30,
	}
	copy(rb.buf[10:30], "01234567890123456789")

	w := rb.window()
	if len(w) != 20 {
		t.Errorf("window() len = %d, want 20", len(w))
	}
	if string(w) != "01234567890123456789" {
		t.Errorf("window() = %q", string(w))
	}

	// Empty when start == end
	rb.start = 50
	rb.end = 50
	if len(rb.window()) != 0 {
		t.Error("window() should be empty when start == end")
	}
}

func TestReadBuffer_Advance(t *testing.T) {
	rb := readBuffer{
		buf:   make([]byte, 100),
		start: 10,
		end:   30,
	}

	// Zero advance is no-op
	rb.advance(0)
	if rb.start != 10 || rb.end != 30 {
		t.Error("advance(0) should be no-op")
	}

	// Partial advance
	rb.advance(5)
	if rb.start != 15 {
		t.Errorf("start = %d, want 15", rb.start)
	}
	if rb.end != 30 {
		t.Errorf("end = %d, want 30", rb.end)
	}

	// Full consumption resets to 0,0
	rb.advance(15)
	if rb.start != 0 || rb.end != 0 {
		t.Errorf("full advance: start=%d, end=%d, want 0,0", rb.start, rb.end)
	}
}

func TestReadBuffer_Compact(t *testing.T) {
	// start=0 is no-op
	rb := readBuffer{
		buf:   make([]byte, 100),
		start: 0,
		end:   20,
	}
	copy(rb.buf[:20], "data at start")
	rb.compact()
	if rb.start != 0 || rb.end != 20 {
		t.Error("compact() with start=0 should be no-op")
	}

	// start==end is no-op
	rb.start = 20
	rb.end = 20
	rb.compact()
	if rb.start != 20 || rb.end != 20 {
		t.Error("compact() with start==end should be no-op")
	}

	// Normal compact: data integrity
	rb = readBuffer{
		buf:   make([]byte, 100),
		start: 50,
		end:   60,
	}
	copy(rb.buf[50:60], "0123456789")
	rb.compact()
	if rb.start != 0 {
		t.Errorf("start = %d, want 0", rb.start)
	}
	if rb.end != 10 {
		t.Errorf("end = %d, want 10", rb.end)
	}
	if string(rb.buf[:10]) != "0123456789" {
		t.Errorf("compacted data = %q, want 0123456789", string(rb.buf[:10]))
	}
}

func TestReadBuffer_EnsureWriteSpace(t *testing.T) {
	t.Run("has space", func(t *testing.T) {
		rb := readBuffer{
			buf: make([]byte, 100),
			end: 50,
		}
		if err := rb.ensureWriteSpace(); err != nil {
			t.Errorf("should have space: %v", err)
		}
	})

	t.Run("compacts to make space", func(t *testing.T) {
		rb := readBuffer{
			buf:   make([]byte, 100),
			start: 80,
			end:   100,
		}
		copy(rb.buf[80:100], "remaining data here!")
		if err := rb.ensureWriteSpace(); err != nil {
			t.Errorf("compact should make space: %v", err)
		}
		if rb.start != 0 {
			t.Errorf("start = %d, want 0 after compact", rb.start)
		}
		if rb.end != 20 {
			t.Errorf("end = %d, want 20 after compact", rb.end)
		}
	})

	t.Run("grows on demand", func(t *testing.T) {
		rb := readBuffer{
			buf:   make([]byte, 1024),
			start: 0,
			end:   1024,
		}
		if err := rb.ensureWriteSpace(); err != nil {
			t.Errorf("should grow: %v", err)
		}
		if len(rb.buf) != 2048 {
			t.Errorf("buf size = %d, want 2048", len(rb.buf))
		}
	})

	t.Run("caps at maxReadBufSize", func(t *testing.T) {
		rb := readBuffer{
			buf:   make([]byte, maxReadBufSize),
			start: 0,
			end:   maxReadBufSize,
		}
		err := rb.ensureWriteSpace()
		if err == nil {
			t.Error("should error at max size")
		}
	})
}

func TestReadBuffer_ReadMore(t *testing.T) {
	client, server := net.Pipe()
	defer func() { _ = client.Close() }()
	defer func() { _ = server.Close() }()

	rb := readBuffer{
		buf: make([]byte, 1024),
	}

	// Write data to server side
	go func() {
		_, _ = server.Write([]byte("hello"))
	}()

	n, err := rb.readMore(client, time.Time{}, false)
	if err != nil {
		t.Fatalf("readMore() error = %v", err)
	}
	if n != 5 {
		t.Errorf("readMore() = %d, want 5", n)
	}
	if rb.end != 5 {
		t.Errorf("end = %d, want 5", rb.end)
	}

	// Deadline caching: same deadline should not cause additional syscall
	dl := time.Now().Add(time.Hour)
	go func() {
		_, _ = server.Write([]byte("world"))
	}()
	_, err = rb.readMore(client, dl, true)
	if err != nil {
		t.Fatalf("readMore() with deadline error = %v", err)
	}
	if !rb.lastHasDeadline || !rb.lastDeadline.Equal(dl) {
		t.Error("deadline should be cached")
	}

	// Same deadline again: should reuse cached
	go func() {
		_, _ = server.Write([]byte("!"))
	}()
	_, err = rb.readMore(client, dl, true)
	if err != nil {
		t.Fatalf("readMore() cached deadline error = %v", err)
	}
}

func TestReadBuffer_FeedUntilDone(t *testing.T) {
	t.Run("immediate done", func(t *testing.T) {
		client, server := net.Pipe()
		defer func() { _ = client.Close() }()

		go func() {
			_, _ = server.Write([]byte("data"))
			_ = server.Close()
		}()

		rb := readBuffer{}
		feeder := &mockFeeder{
			feedFunc: func(in []byte, out io.Writer) (int, bool, error) {
				return len(in), true, nil
			},
		}

		err := rb.feedUntilDone(client, feeder, io.Discard, func(int) (time.Time, bool) {
			return time.Time{}, false
		})
		if err != nil {
			t.Errorf("feedUntilDone() error = %v", err)
		}
	})

	t.Run("multi-read", func(t *testing.T) {
		client, server := net.Pipe()
		defer func() { _ = client.Close() }()

		go func() {
			_, _ = server.Write([]byte("part1"))
			time.Sleep(10 * time.Millisecond)
			_, _ = server.Write([]byte("part2"))
			_ = server.Close()
		}()

		rb := readBuffer{}
		calls := 0
		feeder := &mockFeeder{
			feedFunc: func(in []byte, out io.Writer) (int, bool, error) {
				calls++
				if calls >= 2 {
					return len(in), true, nil
				}
				return len(in), false, nil
			},
		}

		err := rb.feedUntilDone(client, feeder, io.Discard, func(int) (time.Time, bool) {
			return time.Time{}, false
		})
		if err != nil {
			t.Errorf("feedUntilDone() error = %v", err)
		}
		if calls < 2 {
			t.Errorf("calls = %d, want >= 2", calls)
		}
	})

	t.Run("feeder error propagation", func(t *testing.T) {
		client, server := net.Pipe()
		defer func() { _ = client.Close() }()

		go func() {
			_, _ = server.Write([]byte("data"))
			_ = server.Close()
		}()

		rb := readBuffer{}
		feeder := &mockFeeder{
			feedFunc: func(in []byte, out io.Writer) (int, bool, error) {
				return 0, false, io.ErrUnexpectedEOF
			},
		}

		err := rb.feedUntilDone(client, feeder, io.Discard, func(int) (time.Time, bool) {
			return time.Time{}, false
		})
		if err != io.ErrUnexpectedEOF {
			t.Errorf("expected io.ErrUnexpectedEOF, got %v", err)
		}
	})

	t.Run("conn close propagated", func(t *testing.T) {
		client, server := net.Pipe()

		go func() {
			_ = server.Close()
		}()

		rb := readBuffer{}
		feeder := &mockFeeder{
			feedFunc: func(in []byte, out io.Writer) (int, bool, error) {
				return len(in), false, nil
			},
		}

		err := rb.feedUntilDone(client, feeder, io.Discard, func(int) (time.Time, bool) {
			return time.Time{}, false
		})
		_ = client.Close()
		if err == nil {
			t.Error("expected error from closed connection")
		}
	})

	t.Run("compact on zero consume", func(t *testing.T) {
		client, server := net.Pipe()
		defer func() { _ = client.Close() }()

		go func() {
			_, _ = server.Write([]byte("initial"))
			time.Sleep(10 * time.Millisecond)
			_, _ = server.Write([]byte("more"))
			_ = server.Close()
		}()

		rb := readBuffer{}
		calls := 0
		feeder := &mockFeeder{
			feedFunc: func(in []byte, out io.Writer) (int, bool, error) {
				calls++
				if calls == 1 {
					// Consume nothing first time to trigger compact
					return 0, false, nil
				}
				return len(in), true, nil
			},
		}

		err := rb.feedUntilDone(client, feeder, io.Discard, func(int) (time.Time, bool) {
			return time.Time{}, false
		})
		if err != nil {
			t.Errorf("feedUntilDone() error = %v", err)
		}
	})
}
