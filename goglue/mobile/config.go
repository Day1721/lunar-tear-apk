package mobile

import (
	"encoding/json"
	"fmt"
	"net"
	"path/filepath"
	"strconv"
)

// masterDataRelPath mirrors the const at cmd/lunar-tear/main.go:17. Upstream
// resolves it relative to the process working directory; we join it onto the
// assets root so the path handed to runtime.NewHolder is absolute.
const masterDataRelPath = "assets/release/20240404193219.bin.e"

// config is deliberately unexported: gomobile binds every exported type in the
// package, and passing configuration as JSON keeps the bound surface to plain
// strings. Field names match the JSON the Android layer writes.
type config struct {
	// AssetsRoot is the directory containing the assets/ tree, e.g.
	// /sdcard/LunarTear. Must be readable AND writable, because
	// internal/runtime/holder.go:85 calls os.Chtimes on the master-data file.
	AssetsRoot string `json:"assetsRoot"`
	// GameDB is an absolute path to game.db. Kept on app-internal storage:
	// SQLite WAL on FUSE-backed shared storage is unreliable.
	GameDB string `json:"gameDB"`
	// CacheDir becomes TMPDIR. modernc.org/sqlite resolves scratch files via
	// os.TempDir(), which falls back to a non-writable /tmp on Android.
	CacheDir string `json:"cacheDir"`

	GRPCListen     string `json:"grpcListen"`
	GRPCPublicAddr string `json:"grpcPublicAddr"`
	CDNListen      string `json:"cdnListen"`
	CDNPublicAddr  string `json:"cdnPublicAddr"`

	// AuthURL is the auth-server base URL, or empty to run without it. When
	// empty, SetFacebookAccount and TransferUserByFacebook return
	// FailedPrecondition; ordinary UUID login is unaffected.
	AuthURL string `json:"authURL"`

	// AdminListen/AdminToken gate the master-data reload webhook. Fail-closed:
	// an empty token means the listener never binds, mirroring
	// cmd/lunar-tear/admin.go:22-26.
	AdminListen string `json:"adminListen"`
	AdminToken  string `json:"adminToken"`

	NoRegister bool `json:"noRegister"`
}

func parseConfig(cfgJSON string) (*config, error) {
	var c config
	if err := json.Unmarshal([]byte(cfgJSON), &c); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	if c.AssetsRoot == "" {
		return nil, fmt.Errorf("assetsRoot is required")
	}
	if c.GameDB == "" {
		return nil, fmt.Errorf("gameDB is required")
	}
	if !filepath.IsAbs(c.GameDB) {
		return nil, fmt.Errorf("gameDB must be an absolute path, got %q", c.GameDB)
	}
	for _, f := range []struct{ name, addr string }{
		{"grpcListen", c.GRPCListen},
		{"grpcPublicAddr", c.GRPCPublicAddr},
		{"cdnListen", c.CDNListen},
		{"cdnPublicAddr", c.CDNPublicAddr},
	} {
		if err := validateHostPort(f.name, f.addr); err != nil {
			return nil, err
		}
	}
	if c.AdminToken != "" {
		if err := validateHostPort("adminListen", c.AdminListen); err != nil {
			return nil, err
		}
	}
	return &c, nil
}

// validateHostPort rejects the privileged ports an unprivileged Android app
// cannot bind, which is why upstream's 0.0.0.0:443 default is unusable here.
func validateHostPort(field, addr string) error {
	if addr == "" {
		return fmt.Errorf("%s is required", field)
	}
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("%s %q: want host:port: %w", field, addr, err)
	}
	if host == "" {
		return fmt.Errorf("%s %q: host must be set (use 127.0.0.1 or 0.0.0.0)", field, addr)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return fmt.Errorf("%s %q: bad port: %w", field, addr, err)
	}
	if port < 1024 || port > 65535 {
		return fmt.Errorf("%s %q: port must be 1024-65535 (Android apps cannot bind privileged ports)", field, addr)
	}
	return nil
}

func (c *config) masterDataPath() string {
	return filepath.Join(c.AssetsRoot, masterDataRelPath)
}

// octoURL is what the client is told to fetch assets from. Upstream requires
// --octo-url on the game server; we always derive it from the CDN public addr.
func (c *config) octoURL() string {
	return "http://" + c.CDNPublicAddr
}
