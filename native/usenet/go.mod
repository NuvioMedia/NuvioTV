module github.com/NuvioMedia/NuvioTV/native/usenet

replace github.com/mnightingale/rapidyenc => ./third_party/rapidyenc

replace github.com/javi11/nntppool/v4 => ./third_party/nntppool

go 1.27.0

require (
	github.com/dlclark/regexp2/v2 v2.7.2
	github.com/javi11/nntppool/v4 v4.23.0
	github.com/javi11/nzbparser v0.5.5
	github.com/mnightingale/rapidyenc v0.0.0-20251128204712-7aafef1eaf1c
	golang.org/x/net v0.39.0
)

require (
	golang.org/x/sync v0.19.0 // indirect
	golang.org/x/text v0.31.0 // indirect
)
