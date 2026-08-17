// Package mobile is the gomobile-bound surface for the Lunar Tear Android app.
//
// It runs the gRPC game server and the Octo CDN in-process, inside a single Go
// runtime, so both get a real programmatic Start/Stop and typed errors. The auth
// server is NOT here: its HTTP handlers and embedded login.html live in package
// main upstream and cannot be imported, so the Android layer execs it as a
// separate native binary instead.
//
// Everything exported here must stay within gomobile's supported types: plain
// strings, bools, ints, error, and interfaces declared in this package.
package mobile

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"

	"lunar-tear/server/internal/database"
	"lunar-tear/server/internal/gametime"
	"lunar-tear/server/internal/runtime"
	"lunar-tear/server/internal/store/sqlite"
	"lunar-tear/server/migrations"

	"google.golang.org/grpc"
)

// running holds everything that needs tearing down. nil means stopped.
type running struct {
	db       *sql.DB
	grpcSrv  *grpc.Server
	cdnSrv   *http.Server
	adminSrv *http.Server
}

var (
	mu      sync.Mutex
	current *running
)

// SetLogger installs the sink that receives every server log line. Safe to call
// before Start, and safe to pass nil to detach (e.g. when the UI unbinds).
func SetLogger(l Logger) {
	bridge.setSink(l)
}

// LogSnapshot returns the buffered log history, oldest first, newline-joined.
// Lets the UI backfill when it (re)binds to the service.
func LogSnapshot() string {
	return strings.Join(bridge.snapshot(), "\n")
}

// IsRunning reports whether the in-process servers are up.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return current != nil
}

// Start brings up the CDN and the gRPC game server. cfgJSON is the JSON form of
// the config struct in config.go. Returns an error rather than exiting; the one
// failure mode it cannot convert is a log.Fatal deep inside
// internal/masterdata (24 sites) while parsing user-supplied master data, which
// is why the Android side runs this in an isolated :server process.
func Start(cfgJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		return errors.New("server already running")
	}

	cfg, err := parseConfig(cfgJSON)
	if err != nil {
		return err
	}

	// modernc.org/sqlite resolves scratch files through os.TempDir(), which on
	// Android falls back to a non-writable /tmp. Must be set before the first
	// database.Open.
	if cfg.CacheDir != "" {
		if err := os.Setenv("TMPDIR", cfg.CacheDir); err != nil {
			return fmt.Errorf("set TMPDIR: %w", err)
		}
	}

	// internal/service/data.go:19 stats "assets/release/..." relative to the
	// working directory, and it is a const with no flag. Chdir is what makes
	// that resolve without patching upstream. Safe here because this runs in a
	// dedicated Android process.
	if err := os.Chdir(cfg.AssetsRoot); err != nil {
		return fmt.Errorf("chdir to assets root %q: %w", cfg.AssetsRoot, err)
	}

	// Master data first, matching cmd/lunar-tear/main.go:33 — it is the
	// expensive, failure-prone step, so fail before touching the database.
	holder, err := runtime.NewHolder(cfg.masterDataPath())
	if err != nil {
		return fmt.Errorf("init master data: %w", err)
	}

	db, err := database.Open(cfg.GameDB)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	log.Printf("database opened: %s", cfg.GameDB)

	// Upstream never migrates on startup (entrypoint.sh shells out to the goose
	// CLI). migrations.Up is upstream's own embedded runner, so the schema stays
	// in lockstep with whatever commit the submodule is pinned to.
	if err := migrations.Up(context.Background(), db); err != nil {
		db.Close()
		return fmt.Errorf("run migrations: %w", err)
	}

	userStore := sqlite.New(db, gametime.Now)

	cdnSrv, err := startCDN(cfg)
	if err != nil {
		db.Close()
		return fmt.Errorf("start cdn: %w", err)
	}

	grpcSrv, err := startGRPC(cfg, userStore, holder)
	if err != nil {
		_ = cdnSrv.Shutdown(context.Background())
		db.Close()
		return fmt.Errorf("start game server: %w", err)
	}

	current = &running{
		db:       db,
		grpcSrv:  grpcSrv,
		cdnSrv:   cdnSrv,
		adminSrv: startAdmin(cfg, holder),
	}
	log.Printf("lunar-tear started (grpc=%s cdn=%s auth=%q)", cfg.GRPCListen, cfg.CDNListen, cfg.AuthURL)
	return nil
}

// Stop shuts everything down and checkpoints the WAL, mirroring
// cmd/lunar-tear/main.go:54-60. Idempotent.
func Stop() {
	mu.Lock()
	r := current
	current = nil
	mu.Unlock()

	if r == nil {
		return
	}
	log.Println("shutting down...")

	r.grpcSrv.GracefulStop()
	if err := r.cdnSrv.Shutdown(context.Background()); err != nil {
		log.Printf("[cdn] shutdown: %v", err)
	}
	shutdownAdmin(r.adminSrv)

	database.Checkpoint(r.db)
	if err := r.db.Close(); err != nil {
		log.Printf("close database: %v", err)
	}
	log.Println("shutdown complete")
}
