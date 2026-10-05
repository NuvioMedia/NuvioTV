module github.com/NuvioMedia/NuvioTV/native/usenet

replace github.com/mnightingale/rapidyenc => ./third_party/rapidyenc

replace github.com/javi11/nntppool/v4 => ./third_party/nntppool

replace github.com/javi11/sevenzip => ./third_party/sevenzip

go 1.27.0

require (
	github.com/dlclark/regexp2/v2 v2.7.2
	github.com/javi11/nntppool/v4 v4.23.0
	github.com/javi11/nzbparser v0.5.5
	github.com/mnightingale/rapidyenc v0.0.0-20251128204712-7aafef1eaf1c
	golang.org/x/net v0.39.0
)

require (
	github.com/andybalholm/brotli v1.2.0 // indirect
	github.com/bodgit/plumbing v1.3.0 // indirect
	github.com/bodgit/windows v1.0.1 // indirect
	github.com/hashicorp/golang-lru/v2 v2.0.7 // indirect
	github.com/javi11/sevenzip v1.6.2-0.20251026160715-ca961b7f1239 // indirect
	github.com/klauspost/compress v1.18.0 // indirect
	github.com/pierrec/lz4/v4 v4.1.22 // indirect
	github.com/spf13/afero v1.15.0 // indirect
	github.com/ulikunitz/xz v0.5.15 // indirect
	go4.org v0.0.0-20200411211856-f5505b9728dd // indirect
	golang.org/x/sync v0.19.0 // indirect
	golang.org/x/text v0.31.0 // indirect
)
