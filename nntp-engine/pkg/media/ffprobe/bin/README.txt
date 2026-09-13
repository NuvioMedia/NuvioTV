This directory holds prebuilt ffprobe binaries that get embedded into the
streamnzb executable when it is built with the `embedffprobe` build tag.

Do NOT commit the binaries themselves (they are gitignored). Populate them at
build time:

    go run ./tools/fetch-ffprobe

That downloads, per platform, the file each //go:embed directive expects:

    ffprobe-windows-amd64.exe   (embed_windows_amd64.go)
    ffprobe-linux-amd64         (embed_linux_amd64.go)
    ffprobe-linux-arm64         (embed_linux_arm64.go)
    ffprobe-darwin-amd64        (embed_darwin_amd64.go)
    ffprobe-darwin-arm64        (embed_darwin_arm64.go)  # x86_64 build, runs via Rosetta 2

Then build a self-contained binary:

    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -tags embedffprobe ./cmd/streamnzb

Building WITHOUT `-tags embedffprobe` compiles fine with an empty bin/ and falls
back to ffprobe on PATH or the runtime auto-downloader (installer.go).
