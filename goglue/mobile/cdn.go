package mobile

import (
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"

	"lunar-tear/server/internal/service"

	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

// resourcesBaseURLLen is the exact byte length the rewritten resource URL must
// have: it overwrites "https://resources.app.nierreincarnation.com" in place
// inside the list.bin protobuf. See cmd/octo-cdn/main.go:27-35.
const resourcesBaseURLLen = 43

// buildResourcesBaseURL mirrors cmd/octo-cdn/main.go:27-35. Returns "" when the
// public address is too long to fit, in which case list.bin is served
// unmodified (upstream behaviour — a warning, not an error).
func buildResourcesBaseURL(publicAddr string) string {
	prefix := "http://" + publicAddr + "/"
	padLen := resourcesBaseURLLen - len(prefix)
	if padLen < 1 {
		log.Printf("[config] public-addr too long for %d-char resource URL; list.bin will be served unchanged", resourcesBaseURLLen)
		return ""
	}
	return prefix + strings.Repeat("r", padLen)
}

// startCDN mirrors cmd/octo-cdn/main.go. Unlike upstream it returns errors
// instead of calling log.Fatalf/os.Exit, and it never installs a signal
// handler: shutdown is driven by Stop().
//
// NewOctoHTTPServer prewarms its list.bin index at construction
// (internal/service/octo.go:160-162), so a missing or corrupt asset tree
// surfaces here rather than on the first client request.
func startCDN(cfg *config) (*http.Server, error) {
	resourcesBaseURL := buildResourcesBaseURL(cfg.CDNPublicAddr)

	octoServer := service.NewOctoHTTPServer(resourcesBaseURL, cfg.AssetsRoot)
	h2s := &http2.Server{}
	srv := &http.Server{
		Addr:    cfg.CDNListen,
		Handler: h2c.NewHandler(octoServer.Handler(), h2s),
	}
	if err := http2.ConfigureServer(srv, h2s); err != nil {
		return nil, fmt.Errorf("configure http2: %w", err)
	}

	lis, err := net.Listen("tcp", cfg.CDNListen)
	if err != nil {
		return nil, fmt.Errorf("listen on %s: %w", cfg.CDNListen, err)
	}

	log.Printf("Octo CDN listening on %s (HTTP/1.1 + h2c)", lis.Addr())
	log.Printf("public address: %s", cfg.CDNPublicAddr)
	log.Printf("assets directory: %s", cfg.AssetsRoot)

	go func() {
		// Upstream calls os.Exit(1) here (cmd/octo-cdn/main.go:64), which would
		// take down the whole Android process. Log and let Stop() decide.
		if err := srv.Serve(lis); err != nil && err != http.ErrServerClosed {
			log.Printf("[cdn] server error: %v", err)
		}
	}()
	return srv, nil
}
