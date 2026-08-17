package mobile

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"

	"lunar-tear/server/internal/database"
)

// Account is a row for the reclaim UI. Exported because gomobile needs a
// concrete type, but only ever produced as JSON by ListAccounts.
type accountRow struct {
	UserID int64  `json:"userId"`
	Name   string `json:"name"`
	UUID   string `json:"uuid"`
}

// withDB runs fn against the game database. If the server is up we reuse its
// pool rather than opening a second connection to the same WAL file.
func withDB(dbPath string, fn func(*sql.DB) error) error {
	mu.Lock()
	r := current
	mu.Unlock()

	if r != nil {
		return fn(r.db)
	}
	if dbPath == "" {
		return fmt.Errorf("server is stopped and no database path was given")
	}
	db, err := database.Open(dbPath)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()
	return fn(db)
}

// ListAccounts returns the known accounts as a JSON array of
// {userId, name, uuid}, newest first. Accounts with no profile row yet (a client
// that connected but never picked a name) show an empty name.
func ListAccounts(dbPath string) (string, error) {
	var out []accountRow
	err := withDB(dbPath, func(db *sql.DB) error {
		rows, err := db.Query(`
			SELECT u.user_id, COALESCE(p.name, ''), u.uuid
			FROM users u
			LEFT JOIN user_profile p ON p.user_id = u.user_id
			ORDER BY u.user_id DESC`)
		if err != nil {
			return fmt.Errorf("query accounts: %w", err)
		}
		defer rows.Close()
		for rows.Next() {
			var a accountRow
			if err := rows.Scan(&a.UserID, &a.Name, &a.UUID); err != nil {
				return fmt.Errorf("scan account: %w", err)
			}
			out = append(out, a)
		}
		return rows.Err()
	})
	if err != nil {
		return "", err
	}
	if out == nil {
		out = []accountRow{}
	}
	b, err := json.Marshal(out)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// ReclaimAccount is the in-app equivalent of cmd/claim-account: it hands the
// account named `name` the UUID of the most recently created account, then
// deletes that throwaway account. Use it when the game client is reinstalled or
// has its data cleared, changing the UUID it authenticates with
// (internal/service/user.go:66 keys sessions off the UUID alone).
//
// Upstream deletes the throwaway account's rows using a hardcoded 40-entry
// childTables list. We discover the child tables from the live schema instead —
// the schema declares 87 foreign keys to users(user_id) and not one
// ON DELETE CASCADE, so a hardcoded list silently rots every time a migration
// adds a table.
func ReclaimAccount(dbPath, name string) (string, error) {
	if name == "" {
		return "", fmt.Errorf("name is required")
	}
	var summary string
	err := withDB(dbPath, func(db *sql.DB) error {
		var targetID int64
		err := db.QueryRow(`SELECT user_id FROM user_profile WHERE name = ?`, name).Scan(&targetID)
		if err == sql.ErrNoRows {
			return fmt.Errorf("no account found with name %q", name)
		}
		if err != nil {
			return fmt.Errorf("query user_profile: %w", err)
		}

		var targetUUID string
		if err := db.QueryRow(`SELECT uuid FROM users WHERE user_id = ?`, targetID).Scan(&targetUUID); err != nil {
			return fmt.Errorf("query target uuid: %w", err)
		}

		var latestID int64
		var latestUUID string
		if err := db.QueryRow(`SELECT user_id, uuid FROM users ORDER BY user_id DESC LIMIT 1`).Scan(&latestID, &latestUUID); err != nil {
			return fmt.Errorf("query latest account: %w", err)
		}

		if targetID == latestID {
			summary = fmt.Sprintf("%q (id=%d) is already the most recent account; nothing to do", name, targetID)
			return nil
		}

		children, err := tablesReferencingUsers(db)
		if err != nil {
			return err
		}

		tx, err := db.Begin()
		if err != nil {
			return fmt.Errorf("begin transaction: %w", err)
		}
		defer tx.Rollback()

		// Defer FK enforcement to commit time so the delete order across ~40
		// interdependent child tables does not matter.
		if _, err := tx.Exec(`PRAGMA defer_foreign_keys = ON`); err != nil {
			return fmt.Errorf("defer foreign keys: %w", err)
		}
		for _, t := range children {
			if _, err := tx.Exec(fmt.Sprintf(`DELETE FROM %q WHERE user_id = ?`, t), latestID); err != nil {
				return fmt.Errorf("delete from %s: %w", t, err)
			}
		}
		if _, err := tx.Exec(`DELETE FROM users WHERE user_id = ?`, latestID); err != nil {
			return fmt.Errorf("delete throwaway account: %w", err)
		}
		if _, err := tx.Exec(`UPDATE users SET uuid = ? WHERE user_id = ?`, latestUUID, targetID); err != nil {
			return fmt.Errorf("reassign uuid: %w", err)
		}
		// Stale sessions still point at the old UUID.
		if _, err := tx.Exec(`DELETE FROM sessions WHERE user_id = ?`, targetID); err != nil {
			return fmt.Errorf("clear stale sessions: %w", err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit: %w", err)
		}

		summary = fmt.Sprintf("account %d (%s): uuid %s -> %s; account %d deleted (%d child tables cleaned)",
			targetID, name, targetUUID, latestUUID, latestID, len(children))
		log.Printf("[reclaim] %s", summary)
		return nil
	})
	if err != nil {
		return "", err
	}
	return summary, nil
}

// tablesReferencingUsers asks SQLite which tables have a foreign key onto
// users, so the reclaim path adapts to the pinned submodule's migrations.
func tablesReferencingUsers(db *sql.DB) ([]string, error) {
	rows, err := db.Query(`SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`)
	if err != nil {
		return nil, fmt.Errorf("list tables: %w", err)
	}
	var tables []string
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err != nil {
			rows.Close()
			return nil, err
		}
		tables = append(tables, t)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	var children []string
	for _, t := range tables {
		if t == "users" {
			continue
		}
		refs, err := referencesUsers(db, t)
		if err != nil {
			return nil, err
		}
		if refs {
			children = append(children, t)
		}
	}
	return children, nil
}

func referencesUsers(db *sql.DB, table string) (bool, error) {
	// PRAGMA foreign_key_list returns: id, seq, table, from, to, on_update,
	// on_delete, match.
	rows, err := db.Query(fmt.Sprintf(`PRAGMA foreign_key_list(%q)`, table))
	if err != nil {
		return false, fmt.Errorf("foreign_key_list(%s): %w", table, err)
	}
	defer rows.Close()

	cols, err := rows.Columns()
	if err != nil {
		return false, err
	}
	for rows.Next() {
		vals := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return false, err
		}
		for i, c := range cols {
			if c != "table" {
				continue
			}
			if s, ok := asString(vals[i]); ok && s == "users" {
				return true, nil
			}
		}
	}
	return false, rows.Err()
}

func asString(v any) (string, bool) {
	switch s := v.(type) {
	case string:
		return s, true
	case []byte:
		return string(s), true
	}
	return "", false
}
