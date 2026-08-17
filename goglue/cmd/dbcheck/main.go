// Command dbcheck is an on-device self-test for the database stack.
//
// It exercises the parts that are hard to verify from the UI and impossible to
// verify on a host: modernc.org/sqlite's pure-Go SQLite on Android, the 16 goose
// migrations upstream ships, WAL mode, and the shutdown checkpoint. It needs no
// game assets, so it can be run on a fresh device before the asset tree exists.
//
// Build and run:
//
//	CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o dbcheck ./cmd/dbcheck
//	adb push dbcheck /data/local/tmp/ && adb shell /data/local/tmp/dbcheck -db /data/local/tmp/probe.db
package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"lunar-tear/server/internal/database"
	"lunar-tear/server/migrations"
)

func main() {
	dbPath := flag.String("db", "probe.db", "SQLite path to create and migrate")
	tmpDir := flag.String("tmpdir", "", "value for TMPDIR (modernc.org/sqlite scratch files)")
	keep := flag.Bool("keep", false, "keep the database instead of deleting it")
	flag.Parse()

	if *tmpDir != "" {
		// On Android os.TempDir() falls back to a non-writable /tmp, so anything
		// that makes SQLite spill (large sorts, VACUUM) fails without this.
		if err := os.Setenv("TMPDIR", *tmpDir); err != nil {
			fail("set TMPDIR: %v", err)
		}
	}
	fmt.Printf("TMPDIR   = %s\n", os.TempDir())
	fmt.Printf("database = %s\n", *dbPath)

	if !*keep {
		defer cleanup(*dbPath)
	}

	db, err := database.Open(*dbPath)
	if err != nil {
		fail("database.Open: %v", err)
	}
	defer db.Close()
	fmt.Println("open     = ok")

	report(db, "journal_mode", "PRAGMA journal_mode")
	report(db, "foreign_keys", "PRAGMA foreign_keys")

	if err := migrations.Up(context.Background(), db); err != nil {
		fail("migrations.Up: %v", err)
	}
	var applied int
	if err := db.QueryRow(`SELECT count(*) FROM goose_db_version`).Scan(&applied); err != nil {
		fail("count goose_db_version: %v", err)
	}
	fmt.Printf("migrations = %d applied\n", applied)

	var tables int
	if err := db.QueryRow(
		`SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'`,
	).Scan(&tables); err != nil {
		fail("count tables: %v", err)
	}
	fmt.Printf("tables     = %d\n", tables)

	// A real write against the migrated schema, then the same checkpoint Stop()
	// performs. Also proves foreign_keys=ON does not reject a bare users row.
	const probeUUID = "dbcheck-probe"
	if _, err := db.Exec(
		`INSERT INTO users (uuid, platform_type) VALUES (?, ?)`, probeUUID, 1,
	); err != nil {
		fail("insert probe user: %v", err)
	}
	var gotUUID string
	if err := db.QueryRow(
		`SELECT uuid FROM users WHERE uuid = ?`, probeUUID,
	).Scan(&gotUUID); err != nil {
		fail("read probe user: %v", err)
	}
	if _, err := db.Exec(`DELETE FROM users WHERE uuid = ?`, probeUUID); err != nil {
		fail("delete probe user: %v", err)
	}
	fmt.Printf("write/read = ok (%s)\n", gotUUID)

	database.Checkpoint(db)
	fmt.Println("checkpoint = ok")
	fmt.Println("RESULT: PASS")
}

func report(db *sql.DB, label, query string) {
	var v string
	if err := db.QueryRow(query).Scan(&v); err != nil {
		fail("%s: %v", query, err)
	}
	fmt.Printf("%-9s = %s\n", label, v)
}

func cleanup(dbPath string) {
	for _, suffix := range []string{"", "-wal", "-shm"} {
		_ = os.Remove(dbPath + suffix)
	}
	_ = os.Remove(filepath.Dir(dbPath) + "/.keep")
}

func fail(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "FAIL: "+format+"\n", args...)
	fmt.Println("RESULT: FAIL")
	os.Exit(1)
}
