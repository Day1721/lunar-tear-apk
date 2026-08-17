# Lunar Tear for Android

Packages the [lunar-tear](https://github.com/Walter-Sparrow/lunar-tear) game server as an
Android app, so the server runs on the **same phone as the game client** — no PC, no LAN.
Everything binds to loopback by default.

Provides a start/stop UI, configurable ports, and an ongoing notification (with a Stop
action) so Android keeps the server alive in the background.

**`lunar-tear` is never forked.** It is a pinned git submodule at `external/lunar-tear`, and
its working tree stays clean — verify with `git -C external/lunar-tear status`.

## Architecture

The three upstream services are split by what can actually be imported:

| Service | How it runs | Why |
|---|---|---|
| gRPC game server | in-process, via `gomobile` AAR | logic lives in `internal/`, so it is importable |
| Octo asset CDN | in-process, same AAR | `service.NewOctoHTTPServer` is the whole server |
| Auth server | exec'd child process | its handlers + `//go:embed login.html` are in `package main` |

The Go glue lives in `goglue/`, whose module path is **`lunar-tear/server/androidglue`**. That
nesting is deliberate and load-bearing: Go's internal-package rule is enforced on *import
paths*, so a module under `lunar-tear/server/` may import `lunar-tear/server/internal/...`.
A conventionally-named module could not, and the whole in-process design would collapse.

Only one piece of upstream code is duplicated: the 54-line `registerServices` list from
`cmd/lunar-tear/grpc.go`, which is in `package main` and unreachable. `mobile/drift_test.go`
diffs our list against upstream's and **fails the build** if upstream adds a service —
otherwise a missing service degrades silently into `interceptor.UnknownService`, which only
logs.

The foreground service runs in its own `:server` process. `internal/masterdata` has 24
`log.Fatal` sites reached while parsing user-supplied master data, and `log.Fatal` is
`os.Exit`; isolating it means a corrupt asset file reports an error instead of killing the UI.

## Building

Prerequisites:

```bash
brew install go protobuf                     # Go 1.25+
go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.11
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
go install golang.org/x/mobile/cmd/gomobile@latest
sdkmanager "ndk;27.3.13750724"               # gomobile needs r23+; an old r21 will not do
```

Then:

```bash
git submodule update --init
./scripts/build-go.sh                        # make proto, drift check, AAR, auth binary
./gradlew :app:assembleDebug
```

`scripts/build-go.sh` deliberately ignores any inherited `ANDROID_NDK_HOME` (shell profiles
often pin an ancient NDK) and picks the newest installed one; override with
`LUNAR_ANDROID_NDK`. Note `server/gen/` is gitignored upstream and absent from a fresh
checkout, so `make proto` must run before any Go build — its output does not dirty the
submodule.

## Setup on device

1. Grant **All files access** when prompted.
2. Put the asset tree at **`/sdcard/LunarTear`**:

```
/sdcard/LunarTear/
  assets/release/20240404193219.bin.e
  assets/revisions/0/android/list.bin
  assets/revisions/0/android/assetbundle/
  assets/revisions/0/android/resources/
```

The `0/android`, `0/ios`, and un-split `0/` layouts are all accepted; at least one must be
complete. The directory must be **writable** — `internal/runtime/holder.go:85` calls
`os.Chtimes` on the master-data file after a reload.

A system folder picker is not used, and cannot be: SAF returns `content://` URIs, which Go's
`os` package and `http.ServeFile` (`internal/service/octo.go:448`) cannot open. Supporting it
would mean threading an `fs.FS` seam through `internal/service`, i.e. forking upstream.

The SQLite databases live in app-internal storage, not the picked folder — WAL on
FUSE-backed shared storage is unreliable. `TMPDIR` is pointed at the app cache dir because
`modernc.org/sqlite` resolves scratch files via `os.TempDir()`, which falls back to a
non-writable `/tmp` on Android.

## Ports

Defaults follow upstream's wizard, not the raw binaries: the game server's own default of
`0.0.0.0:443` is a privileged port an unprivileged Android app cannot bind.

| Service | Default |
|---|---|
| gRPC game server | `127.0.0.1:8003` |
| Octo CDN | `127.0.0.1:8080` |
| Auth server | `127.0.0.1:3000` |
| Master-data webhook | `127.0.0.1:8082` (off unless enabled) |

**These must match the host:port your patched client was built against.** Changing a port
here means re-patching the client.

## Notes on the auth server

It is optional. Login does **not** go through it: `RegisterUser`
(`internal/service/user.go:42`) and `Auth` (`user.go:66`) authenticate on the client's device
UUID alone. Only `SetFacebookAccount` and `TransferUserByFacebook` need it, via
`resolveAuthToken` (`user.go:261`).

Turning it off therefore costs account *linking and transfer*, not login. The "Reclaim
account" panel covers the common recovery case — a reinstalled client authenticating with a
new UUID — by handing an existing account that new UUID. Unlike upstream's `cmd/claim-account`,
which hardcodes a 40-entry child-table list, this discovers child tables from the live schema
(the schema declares 87 foreign keys to `users` and zero `ON DELETE CASCADE`), so it cannot
rot as migrations add tables.

Note "Disable new registrations" **requires** the auth server: `RegisterUser` hard-denies when
it is set (`user.go:43-51`), so auth becomes the only way in.

## Diagnostics

`goglue/cmd/dbcheck` is an on-device self-test for the database stack — pure-Go SQLite on
Android, the 16 goose migrations, WAL, and the shutdown checkpoint. It needs no game assets:

```bash
cd goglue && CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o /tmp/dbcheck ./cmd/dbcheck
adb push /tmp/dbcheck /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/dbcheck
adb shell /data/local/tmp/dbcheck -db /data/local/tmp/probe.db -tmpdir /data/local/tmp
```

Useful checks once running:

```bash
adb shell ss -ltn | grep -E '8003|8080|3000'   # listeners bound
adb forward tcp:18080 tcp:8080
curl -s http://127.0.0.1:18080/web/termsofuse  # asset-free CDN route (pure Go HTML)
adb forward tcp:18003 tcp:8003
grpcurl -plaintext 127.0.0.1:18003 list        # upstream registers reflection; lists all 38 services
```

`/web/termsofuse` and `grpcurl list` both work without any game assets, which makes them the
quickest way to confirm a running server.
