#!/usr/bin/env bash
# Builds the two Go artifacts the APK needs:
#
#   1. app/libs/lunartear.aar                        — game server + Octo CDN, in-process
#   2. app/src/main/jniLibs/arm64-v8a/libauthserver.so — auth server, exec'd as a child
#
# The lunar-tear submodule is never modified. `make proto` writes into
# server/gen/, which upstream's own .gitignore covers, so the submodule's working
# tree stays clean.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="$REPO/external/lunar-tear/server"
GLUE="$REPO/goglue"
JNILIBS="$REPO/app/src/main/jniLibs/arm64-v8a"

# arm64 only: every device capable of running the game client is arm64, and each
# Go artifact is large enough that a second ABI is not worth doubling the APK.
ANDROID_ABI="arm64-v8a"
GOARCH="arm64"
ANDROID_API=30 # minSdk 30 — MANAGE_EXTERNAL_STORAGE exists from API 30

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# --- prerequisites ------------------------------------------------------------
command -v go >/dev/null || die "go not found (brew install go; needs 1.25+)"
command -v protoc >/dev/null || die "protoc not found (brew install protobuf)"

export PATH="$(go env GOPATH)/bin:$PATH"
command -v protoc-gen-go >/dev/null || die \
  "protoc-gen-go not found: go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.11"
command -v protoc-gen-go-grpc >/dev/null || die \
  "protoc-gen-go-grpc not found: go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest"
command -v gomobile >/dev/null || die \
  "gomobile not found: go install golang.org/x/mobile/cmd/gomobile@latest"

[ -f "$UPSTREAM/go.mod" ] || die "submodule missing — run: git submodule update --init"

# gomobile needs a real NDK (r23+); the toolchain that ships with old SDK
# installs (r21) is too old.
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME
# Deliberately ignore any inherited ANDROID_NDK_HOME: shell profiles often pin an
# ancient NDK (r21 here), which gomobile silently accepts and then fails on.
# Override explicitly with LUNAR_ANDROID_NDK if you need a specific one.
if [ -n "${LUNAR_ANDROID_NDK:-}" ]; then
  ANDROID_NDK_HOME="$LUNAR_ANDROID_NDK"
else
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sed 's:/*$::' | sort -V | tail -1 || true)"
fi
[ -n "$ANDROID_NDK_HOME" ] || die "no NDK found under $ANDROID_HOME/ndk (sdkmanager 'ndk;27.3.13750724')"
ndk_major="$(basename "$ANDROID_NDK_HOME" | cut -d. -f1)"
[ "$ndk_major" -ge 23 ] 2>/dev/null || die \
  "NDK $(basename "$ANDROID_NDK_HOME") is too old for gomobile (needs r23+); sdkmanager 'ndk;27.3.13750724'"
export ANDROID_NDK_HOME
log "NDK: $ANDROID_NDK_HOME"

# --- 1. generated protobuf ----------------------------------------------------
# server/gen/ is gitignored upstream and absent from a fresh checkout, so every
# Go build has to generate it first.
log "generating protobuf stubs (make proto)"
make -C "$UPSTREAM" proto >/dev/null
[ -d "$UPSTREAM/gen/proto" ] || die "make proto produced no gen/proto"

# --- 2. drift guard -----------------------------------------------------------
# Fails if upstream registered a gRPC service that mobile/game.go does not
# mirror. Run before building so the failure is loud and early.
log "checking service-registration mirror against upstream"
( cd "$GLUE" && go test ./mobile/ -run TestRegistrationListMatchesUpstream )

# --- 3. the .aar --------------------------------------------------------------
log "gomobile bind -> app/libs/lunartear.aar"
mkdir -p "$REPO/app/libs"
( cd "$GLUE" && gomobile bind \
    -target="android/$GOARCH" \
    -androidapi "$ANDROID_API" \
    -trimpath \
    -ldflags="-s -w" \
    -o "$REPO/app/libs/lunartear.aar" \
    ./mobile )

# --- 4. the auth-server binary ------------------------------------------------
# Named lib*.so and placed in jniLibs so Android extracts it into
# nativeLibraryDir with the execute bit. Android 10+ W^X forbids exec'ing from
# app data dirs, but nativeLibraryDir is explicitly permitted. Requires
# useLegacyPackaging=true in app/build.gradle.kts, or it is never extracted.
log "cross-compiling auth-server -> jniLibs/$ANDROID_ABI/libauthserver.so"
mkdir -p "$JNILIBS"
( cd "$UPSTREAM" && CGO_ENABLED=0 GOOS=android GOARCH="$GOARCH" \
    go build -trimpath -ldflags="-s -w" -o "$JNILIBS/libauthserver.so" ./cmd/auth-server )

# --- report -------------------------------------------------------------------
log "done"
ls -lh "$REPO/app/libs/lunartear.aar" "$JNILIBS/libauthserver.so" | sed 's/^/    /'
if [ -n "$(git -C "$REPO/external/lunar-tear" status --porcelain)" ]; then
  printf '\033[33mwarning:\033[0m submodule working tree is dirty — it should not be:\n'
  git -C "$REPO/external/lunar-tear" status --short
fi
