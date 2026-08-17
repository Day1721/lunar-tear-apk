package mobile

import (
	"os"
	"regexp"
	"sort"
	"testing"
)

// upstreamGRPC is the one upstream file whose contents we mirror by hand.
const upstreamGRPC = "../../external/lunar-tear/server/cmd/lunar-tear/grpc.go"

const ourGame = "game.go"

var registerRE = regexp.MustCompile(`pb\.Register(\w+)\(`)

// TestRegistrationListMatchesUpstream is the guard for the only piece of
// upstream code we duplicate. registerServices lives in package main
// (cmd/lunar-tear/grpc.go), so it cannot be imported; upstream touched that file
// in 9 of its first 73 commits, most recently to add a service ("Implement Fate
// Boards"). Without this test a service upstream adds would silently fall
// through to interceptor.UnknownService, which only logs — the client would
// misbehave with no build or startup error.
//
// When this fails: re-splice the registerServices body from upstream into
// game.go (see scripts/build-go.sh) and check whether the new constructor needs
// arguments we do not yet thread through.
func TestRegistrationListMatchesUpstream(t *testing.T) {
	want := registrations(t, upstreamGRPC)
	got := registrations(t, ourGame)

	if len(want) == 0 {
		t.Fatalf("found no pb.Register* calls in %s — is the submodule checked out?", upstreamGRPC)
	}

	missing := difference(want, got)
	extra := difference(got, want)

	for _, m := range missing {
		t.Errorf("upstream registers pb.Register%s but %s does not — re-splice registerServices", m, ourGame)
	}
	for _, e := range extra {
		t.Errorf("%s registers pb.Register%s but upstream no longer does — stale mirror", ourGame, e)
	}
	if !t.Failed() {
		t.Logf("%d gRPC service registrations in sync with upstream", len(want))
	}
}

func registrations(t *testing.T, path string) []string {
	t.Helper()
	src, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var out []string
	for _, m := range registerRE.FindAllStringSubmatch(string(src), -1) {
		out = append(out, m[1])
	}
	sort.Strings(out)
	return out
}

func difference(a, b []string) []string {
	inB := make(map[string]bool, len(b))
	for _, s := range b {
		inB[s] = true
	}
	var out []string
	for _, s := range a {
		if !inB[s] {
			out = append(out, s)
		}
	}
	return out
}
