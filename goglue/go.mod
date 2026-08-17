// The module path MUST live under "lunar-tear/server/" so that Go's internal-package
// rule lets us import lunar-tear/server/internal/... In module mode the check is
// performed on import paths, not directories, so a sibling module with a nested path
// is treated as being inside the upstream tree. See scripts/spike-internal-import.sh.
module lunar-tear/server/androidglue

go 1.25.8

require (
	golang.org/x/net v0.58.0
	google.golang.org/grpc v1.80.0
	lunar-tear/server v0.0.0
)

require (
	github.com/dustin/go-humanize v1.0.1 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/mattn/go-isatty v0.0.21 // indirect
	github.com/mfridman/interpolate v0.0.2 // indirect
	github.com/ncruces/go-strftime v1.0.0 // indirect
	github.com/pierrec/lz4/v4 v4.1.26 // indirect
	github.com/pressly/goose/v3 v3.27.1 // indirect
	github.com/remyoudompheng/bigfft v0.0.0-20230129092748-24d4a6f8daec // indirect
	github.com/sethvargo/go-retry v0.3.0 // indirect
	github.com/vmihailenco/msgpack/v5 v5.4.1 // indirect
	github.com/vmihailenco/tagparser/v2 v2.0.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/mobile v0.0.0-20260816165457-f98cc9b3c733 // indirect
	golang.org/x/mod v0.39.0 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.41.0 // indirect
	golang.org/x/tools v0.49.0 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20260420184626-e10c466a9529 // indirect
	google.golang.org/protobuf v1.36.11 // indirect
	modernc.org/libc v1.72.1 // indirect
	modernc.org/mathutil v1.7.1 // indirect
	modernc.org/memory v1.11.0 // indirect
	modernc.org/sqlite v1.49.1 // indirect
)

replace lunar-tear/server => ../external/lunar-tear/server

tool golang.org/x/mobile/cmd/gobind

// gomobile's x/mobile pulls x/net forward, but the CDN's h2c handler should run
// the exact version upstream builds and tests against (server/go.mod pins
// v0.53.0). gobind is a codegen tool and does not need x/net itself, so forcing
// the runtime version here is safe.
replace golang.org/x/net => golang.org/x/net v0.53.0
