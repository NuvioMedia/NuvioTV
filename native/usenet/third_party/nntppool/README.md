# nntppool

A high-performance NNTP connection pool library for Go. It manages multiple NNTP provider connections with pipelining, automatic failover, backup providers, and yEnc/UU decoding — designed for usenet download applications that need maximum throughput across many providers simultaneously.

## Table of Contents

- [Key Features](#key-features)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
  - [Install the dependency](#install-the-dependency)
  - [Basic usage — single provider](#basic-usage--single-provider)
  - [Multiple providers with backup](#multiple-providers-with-backup)
  - [Streaming body to a writer](#streaming-body-to-a-writer)
  - [Async body retrieval](#async-body-retrieval)
  - [Priority requests](#priority-requests)
  - [Background requests](#background-requests)
  - [Check article existence](#check-article-existence)
  - [Fetch article headers](#fetch-article-headers)
  - [Post an article](#post-an-article)
  - [Low-level raw send](#low-level-raw-send)
  - [Dynamic provider management](#dynamic-provider-management)
  - [Download quota management](#download-quota-management)
  - [Application-level keepalive](#application-level-keepalive)
  - [Statistics and monitoring](#statistics-and-monitoring)
- [Architecture Overview](#architecture-overview)
  - [Connection lifecycle](#connection-lifecycle)
  - [Request dispatch strategies](#request-dispatch-strategies)
  - [Failover and retry logic](#failover-and-retry-logic)
  - [Read buffer internals](#read-buffer-internals)
  - [yEnc decoding pipeline](#yenc-decoding-pipeline)
  - [Hot vs cold connections](#hot-vs-cold-connections)
  - [POST two-phase protocol](#post-two-phase-protocol)
- [API Reference](#api-reference)
  - [Creating a client](#creating-a-client)
  - [Reading articles](#reading-articles)
  - [Posting articles](#posting-articles)
  - [Low-level send](#low-level-send)
  - [Provider management](#provider-management)
  - [Statistics](#statistics)
  - [Provider testing](#provider-testing)
  - [Key types](#key-types)
- [Configuration Reference](#configuration-reference)
  - [Provider fields](#provider-fields)
  - [Client options](#client-options)
  - [Dispatch strategies](#dispatch-strategies)
  - [Sentinel errors](#sentinel-errors)
- [Testing](#testing)
- [Speed Test Tool](#speed-test-tool)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

---

## Key Features

- **Multi-provider pooling**: configure N connection slots per provider; supports both main and backup tiers
- **Command pipelining**: configurable inflight requests per connection (default: 1)
- **Weighted round-robin dispatch**: distributes load by available inflight capacity; FIFO mode also available
- **Automatic failover**: on 430 (article not found) the pool retries every main provider before falling back to backups
- **Same-host deduplication**: a 430 from one account on a host skips all other accounts on the same host
- **Provider removal on 502**: permanently unavailable providers are atomically removed from the pool
- **Auto-reconnect after 502**: optionally re-add a provider after a configurable delay (`ReconnectDelay`)
- **yEnc decoding**: SIMD-accelerated via `rapidyenc`, with CRC32 validation and `=ybegin`/`=ypart`/`=yend` metadata
- **UU encoding support**: detection and decoding of UUEncoded articles
- **Streaming delivery**: decode directly to an `io.Writer` without memory buffering
- **Priority channel**: urgent requests preempt the normal queue on already-connected connections
- **Idle timeout**: automatically disconnect and clean up connections that have been idle too long
- **Application-level keepalive**: configurable lightweight NNTP probes to detect zombie connections
- **Dynamic provider management**: add or remove providers at runtime without restarting the client
- **Download quota**: per-provider byte limits with optional rolling reset periods
- **Per-provider stats**: bytes consumed, missing articles, error counts, ping RTT, active/max connections
- **Built-in speed test**: measures throughput using NZB files — useful for benchmarking providers

---

## Tech Stack

- **Language**: Go 1.25+
- **Module**: `github.com/javi11/nntppool/v4`
- **Key dependency**: `github.com/mnightingale/rapidyenc` — SIMD-accelerated yEnc decoder
- **Metrics**: lock-free atomic counters — no external monitoring framework required
- **Test tooling**: standard `testing` package, `golangci-lint v2`, `go-junit-report`, `govulncheck`
- **Linter**: `golangci-lint` via `go tool` (pinned in `go.mod`), enforcing `errcheck`, `exhaustruct`, and more

---

## Prerequisites

- **Go 1.25** or later (the module uses `go tool` for linting and test utilities)
- An NNTP provider account (host, port, username, password) to run integration tests against real servers
- No system packages required — `rapidyenc` is a CGO-free pure-Go library

---

## Getting Started

### Install the dependency

```bash
go get github.com/javi11/nntppool/v4
```

### Basic usage — single provider

```go
package main

import (
    "context"
    "crypto/tls"
    "errors"
    "fmt"
    "log"

    "github.com/javi11/nntppool/v4"
)

func main() {
    ctx := context.Background()

    providers := []nntppool.Provider{
        {
            Host: "news.example.com:563",
            TLSConfig: &tls.Config{
                ServerName:         "news.example.com",
                ClientSessionCache: tls.NewLRUClientSessionCache(0),
            },
            Auth:        nntppool.Auth{Username: "user", Password: "pass"},
            Connections: 20,
            Inflight:    4,
        },
    }

    client, err := nntppool.NewClient(ctx, providers)
    if err != nil {
        log.Fatal(err)
    }
    defer client.Close()

    // Fetch article body (buffered into memory)
    body, err := client.Body(ctx, "some-message-id@example.com")
    if errors.Is(err, nntppool.ErrArticleNotFound) {
        fmt.Println("article not available on this provider")
        return
    }
    if err != nil {
        log.Fatal(err)
    }

    fmt.Printf("Downloaded %d bytes (encoding: %v, CRC valid: %v)\n",
        body.BytesDecoded, body.Encoding, body.CRCValid)
    // body.YEnc contains filename, file size, part info for yEnc articles
    fmt.Printf("File: %s, Part %d of %d\n",
        body.YEnc.FileName, body.YEnc.Part, body.YEnc.Total)
}
```

### Multiple providers with backup

Main providers are tried using round-robin (or FIFO). If all main providers return 430 (article not found), the pool falls back to backup providers.

```go
providers := []nntppool.Provider{
    {
        Host:        "news.provider1.com:563",
        TLSConfig:   &tls.Config{ServerName: "news.provider1.com"},
        Auth:        nntppool.Auth{Username: "u1", Password: "p1"},
        Connections: 30,
        Inflight:    4,
    },
    {
        Host:        "news.provider2.com:563",
        TLSConfig:   &tls.Config{ServerName: "news.provider2.com"},
        Auth:        nntppool.Auth{Username: "u2", Password: "p2"},
        Connections: 20,
        Inflight:    2,
    },
    {
        // Backup: only contacted when all main providers return 430
        Host:        "news.backup-provider.com:119",
        Auth:        nntppool.Auth{Username: "b1", Password: "bp1"},
        Connections: 10,
        Inflight:    1,
        Backup:      true,
    },
}

client, err := nntppool.NewClient(ctx, providers,
    nntppool.WithDispatchStrategy(nntppool.DispatchRoundRobin), // this is the default
)
```

### Streaming body to a writer

Use `BodyStream` to decode directly into any `io.Writer` (file, buffer, pipe) without holding the entire article in memory. Ideal for large multi-gigabyte NZB segments.

```go
f, err := os.Create("output.bin")
if err != nil {
    log.Fatal(err)
}
defer f.Close()

body, err := client.BodyStream(ctx, "message-id@example.com", f)
if err != nil {
    log.Fatal(err)
}

// body.Bytes is nil — decoded bytes went to f
// body.YEnc still has full metadata
fmt.Printf("File: %s, size: %d, part: %d/%d\n",
    body.YEnc.FileName, body.YEnc.FileSize, body.YEnc.Part, body.YEnc.Total)
fmt.Printf("Wire bytes consumed: %d, decoded: %d, CRC valid: %v\n",
    body.BytesConsumed, body.BytesDecoded, body.CRCValid)
```

You can also react to yEnc metadata before decoding begins (for example to open the correct output file by filename):

```go
var outputFile *os.File

body, err := client.BodyStream(ctx, "message-id@example.com", io.Discard,
    func(meta nntppool.YEncMeta) {
        // Called once =ybegin/=ypart is parsed, before any body bytes arrive
        outputFile, _ = os.Create(meta.FileName)
    },
)
```

### Async body retrieval

`BodyAsync` returns a channel immediately so you can fan out multiple segment downloads and collect results concurrently:

```go
type result struct {
    messageID string
    body      *nntppool.ArticleBody
    err       error
}

messageIDs := []string{"seg1@example.com", "seg2@example.com", "seg3@example.com"}

// Dispatch all requests concurrently
channels := make([]<-chan nntppool.BodyResult, len(messageIDs))
for i, id := range messageIDs {
    var buf bytes.Buffer
    channels[i] = client.BodyAsync(ctx, id, &buf)
}

// Collect results
for i, ch := range channels {
    res := <-ch
    if res.Err != nil {
        fmt.Printf("segment %s failed: %v\n", messageIDs[i], res.Err)
        continue
    }
    fmt.Printf("segment %s: %d bytes\n", messageIDs[i], res.Body.BytesDecoded)
}
```

### Priority requests

`BodyPriority` and `SendPriority` enqueue on a separate priority channel. Idle connections prefer priority requests over normal ones, reducing latency for time-sensitive fetches.

```go
// Fetch the most important segment first
body, err := client.BodyPriority(ctx, "critical-segment@example.com")
```

### Background requests

`BodyBackground`, `StatBackground`, `SendBackground` and `StatMany` with
`StatManyOptions.Background` enqueue on a third, lowest lane for work nobody is
waiting on — a PAR2 repair reading a whole release, or the census that prices it.
Every connection reads the lanes in strict order: priority, normal, background.
When a provider is idle, background work may use every connection. While
priority or normal traffic is recent (within a few seconds), background is held
to `Provider.BackgroundFloor` requests in flight (default: a quarter of
`Connections`, at least 1), so a stream never finds more than that many
connections busy with background bodies.

```go
// Read a whole release without delaying playback or imports
body, err := client.BodyBackground(ctx, "segment-0042@example.com")
```

### Check article existence

```go
stat, err := client.Stat(ctx, "message-id@example.com")
if errors.Is(err, nntppool.ErrArticleNotFound) {
    fmt.Println("article not found on any provider")
} else if err != nil {
    log.Fatal(err)
} else {
    fmt.Printf("article exists: number=%d, id=%s\n", stat.Number, stat.MessageID)
}
```

`StatPriority` is the same check dispatched via the priority queue (prefers idle
connections, so a one-off check doesn't queue behind a large BODY). `StatAsync`
returns a channel for a single non-blocking check.

### Bulk existence checks (NZB health checks)

`STAT` is a single-line request with a single-line reply and **no body**, so it is
purely round-trip-latency bound — the ideal command to run massively in parallel.
`StatMany` checks a slice of message-IDs concurrently, streaming a result per ID as
each completes (out of order), and closes the channel when done. A genuine miss is
reported as `ErrArticleNotFound` with a nil `Result` — a normal outcome of a sweep,
not a fatal error:

```go
ids := nzb.SegmentMessageIDs() // e.g. thousands of segments
var have, missing int
for r := range client.StatMany(ctx, ids, nntppool.StatManyOptions{Concurrency: 64}) {
    switch {
    case r.Err == nil:
        have++
    case errors.Is(r.Err, nntppool.ErrArticleNotFound):
        missing++
    default:
        log.Printf("%s: %v", r.MessageID, r.Err)
    }
}
fmt.Printf("availability: %d/%d present\n", have, have+missing)
```

`StatManyOptions`:

| Field | Description |
|-------|-------------|
| `Concurrency` | Max STATs outstanding across the whole pool at once (0 ⇒ 64). |
| `Priority` | Route each STAT through the priority queue. |
| `Background` | Route each STAT through the background lane (see [Background requests](#background-requests)); wins over `Priority`. |
| `Provider` | Restrict every STAT to one named provider group (per-provider availability audit). Empty ⇒ pool-wide with the same failover as `Stat`. |

If `ctx` is cancelled mid-sweep, dispatch stops and in-flight checks are cancelled;
IDs not yet dispatched produce no result, so check `ctx.Err()` after draining.

#### Tuning STAT throughput

Two levers, both usenet-informed:

- **Fan-out across connections** — handled for you: `StatMany` spreads checks over
  every connection via the pool's weighted round-robin. More `Connections` ⇒ more
  parallel checks.
- **Pipeline depth per connection** — set `Provider.StatInflight` higher than
  `Inflight`. Because STAT carries no payload, many checks can be in flight on one
  connection at negligible memory cost, amortising the round-trip. `Inflight`
  bounds concurrent **BODY** responses (keep it modest — `5`–`10` — to cap
  download memory), while `StatInflight` independently sets how deep bodyless
  **STAT** commands pipeline. A general-purpose client can therefore run
  `Inflight: 8, StatInflight: 100`: downloads stay bounded, existence sweeps go
  fast — no separate client needed.

  ```go
  nntppool.Provider{
      Host:         "news.example.com:563",
      Connections:  20,
      Inflight:     8,   // max concurrent BODY per connection
      StatInflight: 100, // STAT pipelines this deep (0 ⇒ same as Inflight)
  }
  ```

  Caveat: replies on a connection are read in order (FIFO), so a STAT queued
  behind an in-progress BODY on the *same* connection waits for that BODY. A pure
  STAT sweep has no bodies in the way and reaches the full `StatInflight` depth.

### Fetch article headers

```go
head, err := client.Head(ctx, "message-id@example.com")
if err != nil {
    log.Fatal(err)
}

fmt.Println("Subject:", head.Headers["Subject"])
fmt.Println("From:", head.Headers["From"])
fmt.Println("Newsgroups:", head.Headers["Newsgroups"])

// All headers are available, including multi-value ones like Received
for k, vals := range head.Headers {
    for _, v := range vals {
        fmt.Printf("%s: %s\n", k, v)
    }
}
```

### Post an article

Articles are yEnc-encoded on the fly during the two-phase POST protocol. The body reader is consumed exactly once; on failure the caller must retry with a fresh reader.

```go
import "github.com/mnightingale/rapidyenc"

data := []byte("Hello usenet, this is my article content")

headers := nntppool.PostHeaders{
    From:       "poster@example.com",
    Subject:    "Test post [1/1] - \"hello.bin\" yEnc (1/1)",
    Newsgroups: []string{"alt.test", "alt.binaries.test"},
    MessageID:  "<unique-id-12345@example.com>",
    Extra: map[string][]string{
        "X-No-Archive": {"Yes"},
    },
}

meta := rapidyenc.Meta{
    Filename: "hello.bin",
    Size:     int64(len(data)),
}

result, err := client.PostYenc(ctx, headers, bytes.NewReader(data), meta)
if errors.Is(err, nntppool.ErrPostingNotPermitted) {
    fmt.Println("server does not allow posting")
} else if err != nil {
    log.Fatal(err)
} else {
    fmt.Printf("Posted successfully: %d %s\n", result.StatusCode, result.Status)
}
```

Use `PostYencTo` with a provider's stable configured `Name` to post to a
specific main or backup provider without fallback:

```go
result, err := client.PostYencTo(ctx, "uploads", headers, bytes.NewReader(data), meta)
```

### Low-level raw send

For NNTP commands not covered by the high-level API, use `Send` directly:

```go
// Send a custom NNTP command and receive the response
payload := []byte("GROUP alt.test\r\n")
respCh := client.Send(ctx, payload, nil)

resp := <-respCh
if resp.Err != nil {
    log.Fatal(resp.Err)
}
fmt.Printf("Status: %d %s\n", resp.StatusCode, resp.Status)

// For multi-line responses, the lines are in resp.Lines
for _, line := range resp.Lines {
    fmt.Println(line)
}
```

### Dynamic provider management

Providers can be added and removed at runtime without restarting the client. This is useful for implementing failover logic in your application, rotating credentials, or adding providers on demand.

```go
// Add a new provider at runtime (non-blocking; ping runs asynchronously)
err := client.AddProvider(nntppool.Provider{
    Host:        "news.newprovider.com:563",
    TLSConfig:   &tls.Config{ServerName: "news.newprovider.com"},
    Auth:        nntppool.Auth{Username: "u3", Password: "p3"},
    Connections: 10,
    Inflight:    2,
})
if err != nil {
    log.Printf("failed to add provider: %v", err)
}

// Remove a provider by name (name = "host:port" or "host:port+username")
err = client.RemoveProvider("news.oldprovider.com:563")
if err != nil {
    log.Printf("provider not found: %v", err)
}

fmt.Printf("Active providers: %d\n", client.NumProviders())
```

Providers that return a 502 (service unavailable) at the command level are automatically removed. To automatically re-add a removed provider after a delay, set `ReconnectDelay`:

```go
nntppool.Provider{
    Host:           "news.example.com:563",
    Connections:    10,
    ReconnectDelay: 5 * time.Minute, // re-add this provider 5 minutes after 502 removal
}
```

### Download quota management

Set per-provider byte limits to avoid exceeding your plan's monthly allowance. Quota state can be persisted across restarts by reading `ProviderStats.QuotaUsed` and `ProviderStats.QuotaResetAt` before shutdown.

```go
providers := []nntppool.Provider{
    {
        Host:        "news.example.com:563",
        Auth:        nntppool.Auth{Username: "user", Password: "pass"},
        Connections: 20,
        Inflight:    4,

        // Limit to 100 GB per 30 days
        QuotaBytes:  100 * 1024 * 1024 * 1024, // 100 GB
        QuotaPeriod: 30 * 24 * time.Hour,

        // On restart: restore state from last run
        // QuotaUsed:   savedUsed,
        // QuotaResetAt: savedResetAt,
    },
}

// Check quota status at runtime
stats := client.Stats()
for _, p := range stats.Providers {
    if p.QuotaBytes > 0 {
        pct := float64(p.QuotaUsed) / float64(p.QuotaBytes) * 100
        fmt.Printf("%s: quota %.1f%% used (%.2f GB / %.2f GB), resets at %s\n",
            p.Name,
            pct,
            float64(p.QuotaUsed)/(1<<30),
            float64(p.QuotaBytes)/(1<<30),
            p.QuotaResetAt.Format(time.RFC3339),
        )
    }
}

// Save state before shutdown
for _, p := range stats.Providers {
    saveQuotaState(p.Name, p.QuotaUsed, p.QuotaResetAt)
}
```

When a provider's quota is exceeded, requests to that provider return `ErrQuotaExceeded` and the pool routes to other providers automatically. The quota counter resets automatically when `QuotaPeriod` elapses.

### Application-level keepalive

TCP keepalive detects dead network paths, but NNTP servers also close connections that have been silent for too long. `KeepaliveInterval` sends a lightweight NNTP probe command periodically so zombie connections are detected before a real request fails.

```go
nntppool.Provider{
    Host:        "news.example.com:563",
    Connections: 20,
    Inflight:    4,

    // Send DATE every 45 seconds on idle connections
    KeepaliveInterval: 45 * time.Second,

    // For servers that don't support DATE, use HELP or CAPABILITIES instead
    // KeepaliveCommand: "HELP",         // expects 100 response
    // KeepaliveCommand: "CAPABILITIES", // expects 101 response
}
```

If the probe receives an unexpected response, the connection is closed and the slot reconnects transparently. Set `SkipPing: true` and `KeepaliveCommand: ""` to disable keepalive entirely for a provider.

### Statistics and monitoring

```go
stats := client.Stats()

fmt.Printf("Total: %.2f MB/s, %d MB consumed, elapsed: %s\n",
    stats.AvgSpeed/(1<<20),
    stats.BytesConsumed/(1<<20),
    stats.Elapsed.Round(time.Second),
)

for _, p := range stats.Providers {
    fmt.Printf("  [%s] active=%d/%d avg=%.2f MB/s missing=%d errors=%d ping=%s\n",
        p.Name,
        p.ActiveConnections, p.MaxConnections,
        p.AvgSpeed/(1<<20),
        p.Missing,
        p.Errors,
        p.Ping.RTT.Round(time.Millisecond),
    )
}
```

Use `TestProvider` to check connectivity before adding a provider to the pool:

```go
result := nntppool.TestProvider(ctx, nntppool.Provider{
    Host:      "news.example.com:563",
    TLSConfig: &tls.Config{ServerName: "news.example.com"},
    Auth:      nntppool.Auth{Username: "user", Password: "pass"},
})
if result.Err != nil {
    fmt.Printf("provider unreachable: %v\n", result.Err)
} else {
    fmt.Printf("ping RTT: %s, server time: %s\n",
        result.RTT, result.ServerTime.Format(time.RFC3339))
}
```

---

## Architecture Overview

### Connection lifecycle

Each provider is represented by a `providerGroup`, which owns:

- `reqCh` — buffered channel (capacity = `Connections`) for normal requests
- `prioCh` — buffered channel (capacity = `Connections`) for priority requests (`SendPriority`)
- `bgCh` — buffered channel (capacity = `Connections`) for background requests (`SendBackground`); read only when `prioCh` and `reqCh` are empty, and capped at `BackgroundFloor` in flight while foreground traffic is recent
- `hotReqCh` / `hotPrioCh` — unbuffered channels; only already-connected (hot) connections listen here

Each connection slot runs as a `runConnSlot` goroutine in one of three states:

```
IDLE → wait for a request on reqCh/prioCh (zero TCP resources held)
  ↓
CONNECTING → acquire connGate slot, dial, TLS handshake, authenticate
  ↓
ACTIVE → Run() (two goroutines: writeLoop + readerLoop)
  ↓
IDLE (reconnect loop after death/idle timeout)
```

The `Run()` method launches two goroutines that share a `pending` channel:

- **writeLoop** (the goroutine calling `Run()`): reads from `pending`, writes NNTP commands to the TCP connection buffered with a 4KB `bufio.Writer`; handles the POST two-phase handshake
- **readerLoop** (spawned goroutine): reads NNTP responses in FIFO order via `readBuffer.feedUntilDone()`, decodes yEnc/UU content, and delivers responses to `req.RespCh`

The `pending` channel has capacity = `Inflight`, enforcing the maximum pipeline depth.

When a request arrives at `Send()`:

```
client.Send()
  → doSendWithRetry() (goroutine)
    → round-robin / FIFO: pick provider group
    → try hotReqCh (non-blocking) — succeeds only if a connection is already idle with inflight capacity
    → fall back to reqCh (wakes a cold slot or queues behind in-flight requests)
    → receive response from innerCh
    → on 430: retry next provider, track host to skip duplicates
    → on 502: remove provider, retry
    → on all exhausted: deliver last response or error
```

### Request dispatch strategies

**Round-Robin (default)**: Uses dynamic weighted round-robin where each provider's weight equals its current available inflight capacity (`allowed - held`). A provider with 10 free slots gets 10× the traffic of one with 1. The `nextIdx` atomic counter selects the start index via a cumulative-weight binary search. Quota-exceeded providers receive weight 0.

**FIFO**: Scans providers in declaration order and sends to the first provider with available capacity and within quota. Under light load this concentrates traffic on the primary provider, keeping it "warm" while other providers stay disconnected.

Both strategies skip quota-exceeded providers during normal dispatch. If all providers are quota-exceeded, the pool falls back to round-robin and lets each provider return `ErrQuotaExceeded`.

### Failover and retry logic

```
1. Attempt all main providers (round-robin start, then sequentially):
   - 2xx → success, return response immediately
   - 430/423 → article not found on this provider:
       • record host in skipHosts (up to 4)
       • skip other providers on the same host (different credentials won't help)
       • try next provider
   - 502 → permanent unavailability:
       • atomically remove provider from pool
       • if ReconnectDelay > 0: schedule re-add after delay
       • try next provider
   - connection error → try next provider
   - quota exceeded → skip, try next provider

2. If all mains returned 430: attempt backup providers in order
   - backup 430 → still deliver (no further retry)
   - backup 502 → remove, try next backup

3. If all providers exhausted:
   - if any 430 was received → return that 430 response
   - else → return last error or "all providers exhausted"
```

### Read buffer internals

The `readBuffer` (`readBuffer.go`) is a contiguous byte slice used for all reads from the TCP connection:

- **Initial size**: 128KB (configurable via `defaultReadBufSize`)
- **Maximum size**: 8MB (configurable via `maxReadBufSize`)
- **Growth**: doubles on overflow until max; returns an error if max is exceeded
- **Compaction**: moves unread bytes to the front when there is leftover data and no room to write
- **Shrink on reconnect**: buffers are reused across reconnections (stored on the slot goroutine's stack) to avoid re-allocation and re-growth after the first large article
- **Deadline caching**: caches the last `SetReadDeadline` call; only issues the syscall when the deadline changes, reducing overhead on pipelined requests

### yEnc decoding pipeline

The `NNTPResponse` type in `reader.go` implements `streamFeeder` and processes raw NNTP bytes incrementally:

1. **Status line**: reads and parses `NNN message` to extract the status code
2. **Format detection**: after the status line, scans header lines for `=ybegin` (yEnc) or `begin`/UU heuristics (line length 60–61 starting with `M`)
3. **yEnc path**:
   - Parses `=ybegin` for filename, total size, part number
   - Parses `=ypart` for byte range (begin/end), fires `onMeta` callback
   - Delegates to `rapidyenc.DecodeIncremental()` for SIMD-accelerated in-place decoding
   - Accumulates CRC32 using `crc32.Update()` on each decoded chunk
   - Parses `=yend` for `pcrc32=` or `crc32=` field
   - Returns `ErrCRCMismatch` alongside the body when CRCs differ
4. **UU path**: detected but not decoded further (format is noted in `ArticleBody.Encoding`)
5. **NNTP terminator**: `.\r\n` detected by `rapidyenc.DecodeIncremental` returning `EndArticle`; backs up 3 bytes to include the terminator in subsequent header parsing

### Hot vs cold connections

Connections are lazy (on-demand):

- A slot goroutine sits idle in `IDLE` state, holding no TCP connection, until `reqCh` receives a request.
- Once connected, the slot writes to `hotReqCh` in the `Run()` loop, signalling that it is a "hot" (live) connection available for immediate dispatch.
- `doSendWithRetry` first tries `hotReqCh` with a non-blocking select. If a hot connection is waiting with capacity, the request is dispatched without queuing — this is the fast path for already-loaded connections.
- If no hot connection has capacity, the request goes to `reqCh`, which may wake a cold slot or queue behind in-flight requests on the first available slot.

This model means that under light load only a few connections are active, and under heavy load all slots warm up automatically.

### POST two-phase protocol

The NNTP POST command requires a two-step handshake that prevents pipelining other requests during the operation:

1. Client sends `POST\r\n`
2. Server responds `340 send article` (or `440 posting not permitted`)
3. Client streams the article headers + blank line + yEnc body + `\r\n.\r\n` terminator
4. Server responds `240 article posted` (or `441 posting failed`)

The pool implements this with a `postReadyCh` coordination channel between writeLoop and readerLoop:

- writeLoop sends `POST\r\n`, flushes immediately, pushes the request to `pending`
- readerLoop reads the `340`, sends `nil` on `postReadyCh`
- writeLoop receives `nil`, streams the article body via `io.Copy(bw, req.PayloadBody)`
- readerLoop reads the final `240/441` and delivers it to `req.RespCh`

If the server returns `440`, readerLoop sends an error on `postReadyCh` and writeLoop drains `req.PayloadBody` to unblock the pipe-writer goroutine in `PostYenc`.

---

## API Reference

### Creating a client

```go
func NewClient(ctx context.Context, providers []Provider, opts ...ClientOption) (*Client, error)
```

Validates all providers, pings each (unless `SkipPing` is set), and starts connection slot goroutines. Returns an error if:

- `providers` is empty
- all providers are `Backup: true` (at least one non-backup required)
- any provider has `Connections <= 0`
- any provider has neither `Host` nor `Factory`
- two providers resolve to the same name

Provider names default to `host:port` or `host:port+username` (when auth is set). Factory-based providers without `Host` are named `provider-N`.

### Reading articles

| Method | Signature | Description |
|--------|-----------|-------------|
| `Body` | `(ctx, messageID, onMeta...) (*ArticleBody, error)` | Fetch and decode body, buffer entire result in memory |
| `BodyStream` | `(ctx, messageID, w, onMeta...) (*ArticleBody, error)` | Decode and stream to `io.Writer`; `body.Bytes` is nil |
| `BodyAsync` | `(ctx, messageID, w, onMeta...) <-chan BodyResult` | Non-blocking fan-out; returns channel receiving exactly one `BodyResult` |
| `BodyPriority` | `(ctx, messageID, onMeta...) (*ArticleBody, error)` | Like `Body` but dispatched via the priority queue |
| `BodyBackground` | `(ctx, messageID, onMeta...) (*ArticleBody, error)` | Like `Body` but dispatched via the background lane |
| `Head` | `(ctx, messageID) (*ArticleHead, error)` | Fetch RFC 5322 headers; returns parsed `map[string][]string` with folding resolved |
| `Stat` | `(ctx, messageID) (*StatResult, error)` | Check article existence without transferring body |
| `StatPriority` | `(ctx, messageID) (*StatResult, error)` | Like `Stat` but dispatched via the priority queue |
| `StatBackground` | `(ctx, messageID) (*StatResult, error)` | Like `Stat` but dispatched via the background lane |
| `StatAsync` | `(ctx, messageID) <-chan StatManyResult` | Non-blocking single existence check; channel receives exactly one result |
| `StatMany` | `(ctx, messageIDs, StatManyOptions) <-chan StatManyResult` | Concurrent bulk existence check; streams one result per ID as it completes |

The `onMeta` optional callback is called once `=ybegin`/`=ypart` is fully parsed (before any body bytes), enabling pre-allocation or filename routing.

### Posting articles

```go
func (c *Client) PostYenc(ctx context.Context, headers PostHeaders, body io.Reader, meta rapidyenc.Meta) (*PostResult, error)
func (c *Client) PostYencTo(ctx context.Context, provider string, headers PostHeaders, body io.Reader, meta rapidyenc.Meta) (*PostResult, error)
```

yEnc-encodes `body` on the fly and posts using the two-phase NNTP POST protocol. `PostYenc` uses the configured dispatch strategy; `PostYencTo` restricts the request to the named main or backup provider without fallback. The provider's configured `Name` is preferred; the derived provider name remains available when `Name` is empty. The body reader is consumed exactly once.

### Low-level send

```go
func (c *Client) Send(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta ...func(YEncMeta)) <-chan Response
func (c *Client) SendPriority(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta ...func(YEncMeta)) <-chan Response
func (c *Client) SendBackground(ctx context.Context, payload []byte, bodyWriter io.Writer, onMeta ...func(YEncMeta)) <-chan Response
```

All three return immediately with a buffered channel (capacity 1). The caller receives exactly one `Response`. Use `bodyWriter = nil` to buffer decoded bytes in `Response.Body`; use `io.Discard` to throw them away; use any `io.Writer` to stream them.

### Provider management

```go
func (c *Client) AddProvider(p Provider) error
func (c *Client) RemoveProvider(name string) error
func (c *Client) NumProviders() int
```

`AddProvider` validates, pings (unless `SkipPing`), starts connection slots, and atomically appends to main or backup groups. Returns an error on validation failure or duplicate name.

`RemoveProvider` cancels the group's context (causing all slot goroutines to exit), stops the `connGate`, and atomically removes it from the pool. Goroutines wind down asynchronously; `Client.Close()` waits for all via a `sync.WaitGroup`.

### Statistics

```go
func (c *Client) Stats() ClientStats
```

Returns a lock-free snapshot using atomic reads. `ClientStats` aggregates across all providers; `ProviderStats` contains per-provider counters including quota state.

### Provider testing

```go
func TestProvider(ctx context.Context, p Provider) PingResult
```

Dials a temporary connection, authenticates, sends DATE, and returns RTT + server time. Completely independent of any `Client` — useful for pre-flight checks.

### Key types

```go
// ArticleBody is the result of Body/BodyStream/BodyAsync.
type ArticleBody struct {
    MessageID     string
    Bytes         []byte          // nil when BodyStream was used
    BytesDecoded  int             // decoded payload bytes
    BytesConsumed int             // wire bytes consumed (pre-decode)
    Encoding      ArticleEncoding // EncodingYEnc | EncodingUU | EncodingUnknown
    YEnc          YEncMeta        // yEnc metadata (zero value for non-yEnc)
    CRC           uint32          // actual CRC of decoded bytes
    ExpectedCRC   uint32          // CRC from =yend (0 when absent)
    CRCValid      bool            // true when ExpectedCRC != 0 && CRC == ExpectedCRC
}

// YEncMeta holds fields from =ybegin and =ypart headers.
type YEncMeta struct {
    FileName  string // from =ybegin name=
    FileSize  int64  // from =ybegin size= (total file)
    Part      int64  // from =ybegin part= (0 for single-part)
    PartBegin int64  // from =ypart begin= (0-based byte offset)
    PartSize  int64  // derived from =ypart end= - begin
    Total     int64  // from =ybegin total= (total parts)
}

// PostHeaders holds RFC 5322 headers for a POST command.
type PostHeaders struct {
    From       string              // required: "user@example.com"
    Subject    string              // required
    Newsgroups []string            // required: ["alt.test"]
    MessageID  string              // recommended: "<unique@domain>"
    Extra      map[string][]string // additional headers (sorted for determinism)
}

// StatResult is the result of a STAT command.
type StatResult struct {
    MessageID string
    Number    int64 // article number in current group (0 if no group selected)
}

// ArticleHead holds the result of a HEAD command.
type ArticleHead struct {
    MessageID string
    Headers   map[string][]string // RFC 5322 headers, multi-value, folding resolved
}

// ProviderStats is a snapshot of one provider's metrics.
type ProviderStats struct {
    Name              string
    AvgSpeed          float64       // bytes/sec since client start
    BytesConsumed     int64         // raw wire bytes
    Missing           int64         // 430/423 responses
    Errors            int64         // network errors and bad status codes
    ActiveConnections int           // currently running connection slots
    MaxConnections    int           // configured Connections value
    Ping              PingResult    // result of startup DATE ping
    QuotaBytes        int64         // 0 = unlimited
    QuotaUsed         int64         // bytes consumed in current period
    QuotaResetAt      time.Time     // when period resets (zero if no period)
    QuotaExceeded     bool
}
```

---

## Configuration Reference

### Provider fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `Host` | `string` | — | Server address as `host:port`, e.g. `news.example.com:563` |
| `TLSConfig` | `*tls.Config` | nil (plain TCP) | Pass a `tls.Config` to enable TLS; set `ServerName` for SNI |
| `Auth` | `Auth` | — | `Username` and `Password` for AUTHINFO handshake |
| `Connections` | `int` | — | **Required.** Number of connection slots for this provider |
| `Inflight` | `int` | 1 | Max concurrent BODY (and other body-bearing) commands per connection |
| `StatInflight` | `int` | 0 (= `Inflight`) | Pipeline depth for bodyless STAT commands; set higher than `Inflight` (e.g. 50–100) to amortise round-trips on existence sweeps without inflating BODY memory |
| `Factory` | `ConnFactory` | nil | Custom dialer `func(ctx) (net.Conn, error)`; overrides `Host`/`TLSConfig` |
| `Backup` | `bool` | false | If true, only used when all main providers return 430 |
| `SkipPing` | `bool` | false | Skip the startup DATE ping (for servers that don't support DATE) |
| `IdleTimeout` | `time.Duration` | 0 (disabled) | Disconnect idle connections after this duration; 0 = never |
| `ThrottleRestore` | `time.Duration` | 30s | How long to wait before restoring throttled slots after a 502/400 greeting |
| `KeepAlive` | `time.Duration` | 30s | TCP keep-alive interval; negative disables OS-level keep-alive |
| `ReconnectDelay` | `time.Duration` | 0 (disabled) | If set, re-adds the provider this long after a 502 removal |
| `KeepaliveInterval` | `time.Duration` | 0 (disabled) | Application-level probe interval; 0 or when `SkipPing && KeepaliveCommand == ""` disables |
| `KeepaliveCommand` | `string` | `"DATE"` | NNTP command for application-level probe: `"DATE"` (111), `"HELP"` (100), `"CAPABILITIES"` (101) |
| `UserAgent` | `string` | `""` | Sent as `X-User-Agent` or equivalent; empty disables |
| `QuotaBytes` | `int64` | 0 (unlimited) | Maximum bytes per `QuotaPeriod`; 0 = unlimited |
| `QuotaPeriod` | `time.Duration` | 0 (no reset) | Rolling window for quota reset; 0 = lifetime cap |
| `QuotaUsed` | `int64` | 0 | Bytes already consumed at startup (for state restoration) |
| `QuotaResetAt` | `time.Time` | zero | Quota reset deadline at startup (for state restoration) |

### Client options

```go
// Set the request dispatch strategy (default: DispatchRoundRobin)
nntppool.WithDispatchStrategy(nntppool.DispatchRoundRobin)
nntppool.WithDispatchStrategy(nntppool.DispatchFIFO)
```

### Dispatch strategies

| Strategy | Behavior | Best for |
|----------|----------|----------|
| `DispatchRoundRobin` | Weighted by available inflight capacity. Providers with more free slots receive proportionally more requests. Quota-exceeded providers get weight 0. | Most use cases; maximizes throughput across heterogeneous providers |
| `DispatchFIFO` | First provider with available capacity and within quota gets the request. Cascades to subsequent providers when saturated. | Cases where you want to drain the primary provider before touching others (e.g., to minimize connections on low-priority providers) |

### Sentinel errors

| Error | NNTP Code | Meaning |
|-------|-----------|---------|
| `ErrArticleNotFound` | 430 or 423 | Article does not exist on this provider (semantic match: both codes satisfy `errors.Is`) |
| `ErrPostingNotPermitted` | 440 | Server does not allow posting |
| `ErrPostingFailed` | 441 | Server rejected the article |
| `ErrAuthRequired` | 480 | Authentication required before this command |
| `ErrAuthRejected` | 481 | Authentication credentials rejected |
| `ErrServiceUnavailable` | 502 | Server permanently unavailable; provider removed from pool |
| `ErrCRCMismatch` | — | yEnc CRC32 validation failed; body is returned alongside the error |
| `ErrMaxConnections` | 502/400 | Server reported max connections reached during handshake |
| `ErrConnectionDied` | — | TCP connection closed unexpectedly |
| `ErrProtocolDesync` | — | Binary data received where a status line was expected |
| `ErrQuotaExceeded` | — | Provider's download quota for the current period is exhausted |

`ErrArticleNotFound` uses category matching: `errors.Is(err, ErrArticleNotFound)` returns true for both 430 and 423 responses.

`ErrCRCMismatch` is returned alongside a non-nil `*ArticleBody` so callers can inspect the decoded data and decide whether to discard or use it.

### AttemptTimeoutError

When every provider's attempt expires before a first response byte arrives, the
terminal error wraps `*AttemptTimeoutError` instead of surfacing as a bare
"all providers exhausted":

```go
var at *nntppool.AttemptTimeoutError
if errors.As(err, &at) {
    // at.Provider — which provider's attempt expired last
    // at.Timeout  — the attempt window that expired
    // at.Phase    — PhaseDispatch (saturated: never got a connection) or
    //               PhaseResponse (sent, but no first byte within the window)
    // at.Cause    — the underlying transport error, when one exists
}
```

`Phase == "response"` is the slow-spool signature: aged articles can take
several seconds to answer 430 on an otherwise healthy provider. The pool
already reacts on its own — after a pass in which every provider expired this
way, the expired providers are retried once with a wider window (bounded by a
single shared wall-clock budget) — so this error means even the widened window
went unanswered.

---

## Testing

### Running the test suite

```bash
# Run all tests
go test ./...

# With race detector (required before committing)
go test -race ./...

# Run via the Makefile (generate + tidy + lint + race tests)
make check

# Tests only (no lint)
make test

# Tests with race detector (Makefile target)
make test-race

# Specific package
go test ./nzb/...

# Specific test
go test -run TestClient_SendRetryRoundRobin ./...

# Specific test with verbose output
go test -v -run TestNNTPConnection_RunBodyRequest ./...
```

### Coverage

```bash
# Generate coverage profile
make coverage                  # → coverage.out

# View in browser
make coverage-html             # → coverage.html

# Print per-function summary
make coverage-func

# Print total percentage only
make coverage-total

# Coverage with race detector (CI mode)
make coverage-ci
```

### JUnit XML output (CI)

```bash
make junit                     # → test-results/report.xml
```

### Benchmarks

```bash
go test -bench=. -benchmem ./...

# Specific benchmark, longer run
go test -bench=BenchmarkRoundRobin -benchmem -benchtime=10s ./...
```

Built-in benchmarks cover:

- Equal-weight two-provider round-robin (3+3 connections)
- Weighted two-provider round-robin (5+1 connections)
- Single-provider baseline

### Writing tests

The project uses the standard `testing` package with no assertion libraries. Tests should:

- Use table-driven tests where appropriate
- Have descriptive names and failure messages
- Avoid global state — see `testutil.StartMockNNTPServer` for the mock server pattern
- Use `testutil.EncodeYenc` / `EncodeYencMultiPart` to generate yEnc test data

```go
// Example: mock server with yEnc body
conn := mockServer(t, func(s net.Conn) {
    _, _ = s.Write([]byte("200 server ready\r\n"))
    buf := make([]byte, 1024)
    _, _ = s.Read(buf) // consume BODY command
    _, _ = s.Write(yencSinglePart([]byte("Hello world"), "test.bin"))
})
```

Aim for 100% coverage on new code. The project follows Google's [Go testing guidelines](https://google.github.io/styleguide/go/decisions.html#useful-test-failures).

### Security scanning

```bash
go tool govulncheck ./...
```

---

## Speed Test Tool

`cmd/speedtest` measures download throughput through the pool using NZB files. By default it uses the SABnzbd 10GB test NZB; you can point it at any NZB file or URL.

### Build

```bash
go build ./cmd/speedtest
```

### Usage — single provider (legacy flags)

```bash
./speedtest \
    --host news.example.com:563 \
    --tls \
    --user myuser \
    --pass mypassword \
    --conns 20 \
    --inflight 4
```

### Usage — multiple providers

Use `--provider` flags (repeatable) for full control over each provider:

```bash
./speedtest \
    --provider "host=news.provider1.com:563,tls,user=u1,pass=p1,conns=20,inflight=4" \
    --provider "host=news.provider2.com:119,user=u2,pass=p2,conns=10,inflight=2,backup" \
    --max-segments 500
```

### Provider flag syntax

The `--provider` value is a comma-separated list of `key=value` pairs:

| Key | Example | Description |
|-----|---------|-------------|
| `host` | `host=news.example.com:563` | Server address (required) |
| `tls` | `tls` or `tls=true` | Enable TLS; SNI derived from `host` |
| `user` | `user=myuser` | NNTP username |
| `pass` | `pass=mypassword` | NNTP password |
| `conns` | `conns=20` | Connection slots (default: 10) |
| `inflight` | `inflight=4` | Pipelined requests per connection (default: 1) |
| `backup` | `backup` | Mark as backup provider |
| `idle` | `idle=30s` | Idle disconnect timeout |
| `throttle` | `throttle=60s` | Throttle restore duration after 502 |
| `keepalive` | `keepalive=60s` | TCP keep-alive interval |

### Other flags

| Flag | Default | Description |
|------|---------|-------------|
| `--nzb` | SABnzbd 10GB test NZB | Local path or URL to an NZB file |
| `--max-segments` | 0 (all) | Limit the number of segments to download |
| `--provider-name` | all | Test only a specific named provider |

### Example output

```
Provider 1: news.example.com:563 (TLS: yes, conns: 20, inflight: 4, main)
Creating client with 20 connection slots across 1 provider(s)...

[ 15.3s]  450/1250 segs | wire: 142.3 MB/s (avg 138.7 MB/s) | ETA: 28s
[ 30.1s]  920/1250 segs | wire: 139.8 MB/s (avg 139.2 MB/s) | ETA: 12s

=== Speed Test Results ===
Time:       45.123s
Segments:   1250 done, 0 missing, 0 errors
Wire:       1024.00 MB (22.70 MB/s)
Decoded:    981.44 MB (21.76 MB/s)

Provider: news.example.com:563
  Active: 20/20  Missing: 0  Errors: 0  Ping: 12ms
```

---

## Troubleshooting

### Connection refused or timeout

**Symptom**: `NewClient` hangs or connections fail immediately.

**Check**:

- Verify reachability: `nc -zv news.example.com 563`
- Confirm TLS settings match the port — port 563 typically requires TLS; port 119 is plain TCP
- For providers that don't support the DATE command on startup, set `SkipPing: true`

```go
nntppool.Provider{
    Host:      "news.example.com:563",
    SkipPing:  true,
}
```

### Authentication rejected (ErrAuthRejected)

**Symptom**: `ErrAuthRejected` on every request.

**Check**:

- Credentials are correct (test with `TestProvider`)
- Some providers require `user@domain.com` format for the username
- If using `--provider` in the speedtest CLI, usernames/passwords containing commas will break parsing — use the legacy `--user`/`--pass` flags instead

### All articles return 430

**Symptom**: `ErrArticleNotFound` for articles known to exist.

**Check**:

- The provider may not carry the newsgroup or its retention window has expired
- Add a backup provider with longer retention: `Backup: true`
- Verify message ID format — `Body`/`Head`/`Stat` accept the raw message ID without angle brackets; the library adds `<...>` automatically in the NNTP payload

### Max connections throttled (502/400 during handshake)

**Symptom**: `ErrMaxConnections` during connect; fewer active connections than configured.

**Behaviour**: The `connGate` automatically reduces active slots to `max(1, currently_running)` and restores them after `ThrottleRestore` (default 30s). Adjust for slow-recovering providers:

```go
nntppool.Provider{
    ThrottleRestore: 2 * time.Minute,
}
```

### Provider removed from pool

**Symptom**: `NumProviders()` decreases; `ErrServiceUnavailable` returned.

**Cause**: A connection returned 502 at the command level (not just during handshake). To auto-reconnect after a delay:

```go
nntppool.Provider{
    ReconnectDelay: 5 * time.Minute,
}
```

To re-add manually:

```go
err := client.AddProvider(myProvider)
```

### CRC mismatch on decoded bodies

**Symptom**: `ErrCRCMismatch` returned alongside a non-nil `*ArticleBody`.

**Behaviour**: The body is always returned even on CRC mismatch so callers can choose to discard or accept the data. Check `body.CRCValid` and compare `body.CRC` with `body.ExpectedCRC`. This typically indicates a corrupt article on the server side.

### Zombie connections under idle load

**Symptom**: Connections silently stop working after extended idle periods.

**Solution**: Enable application-level keepalive:

```go
nntppool.Provider{
    KeepaliveInterval: 45 * time.Second,
}
```

If the server does not support DATE, use a different probe command:

```go
nntppool.Provider{
    KeepaliveInterval: 45 * time.Second,
    KeepaliveCommand:  "HELP",
}
```

### Race conditions in tests

**Symptom**: Test failures that appear only with `-race`.

**Fix**: Any shared state accessed from the mock server goroutine must be protected with a mutex. See `TestClient_SendRetryRoundRobin` in the test suite for the correct pattern.

### Linter errors (`errcheck`)

**Symptom**: `golangci-lint` fails with unchecked error returns on `io.Pipe*` methods.

**Fix**: The linter enforces that `io.PipeWriter.CloseWithError` and `io.PipeReader.Close` return values are handled. Use the blank identifier explicitly:

```go
defer func() { _ = pw.CloseWithError(err) }()
_ = pr.Close()
```

### Build and lint

```bash
# Full check: generate + tidy + lint + race tests
make check

# Lint only
make golangci-lint

# Auto-fix lint issues
make golangci-lint-fix

# Tidy go.mod
make tidy
```

Note: macOS linker warnings about `LC_DYSYMTAB` in the test output are harmless noise from the system linker and can be ignored.

---

## Contributing

1. Fork the repository and create a topic branch
2. Add tests for your change — aim for 100% coverage on new code
3. Run `make check` — this runs code generation, `go mod tidy`, `golangci-lint`, and the full race-detector test suite
4. Open a pull request

Install the pre-commit hook to run the full check automatically on every commit:

```bash
make git-hooks
```

The project uses the standard `testing` package only — no third-party assertion libraries. See [CONTRIBUTING.md](CONTRIBUTING.md) for additional guidelines.

---

## License

MIT — see [LICENSE](LICENSE) for details.
