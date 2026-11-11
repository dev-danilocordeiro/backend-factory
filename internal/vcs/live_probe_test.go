package vcs

import (
	"context"
	"os"
	"testing"
)

func TestLiveBranchProtected(t *testing.T) {
	repo := os.Getenv("FACTORY_LIVE_REPO")
	if repo == "" {
		t.Skip("set FACTORY_LIVE_REPO=owner/name to run against GitHub")
	}
	gh := GH{Repo: repo}
	ctx := context.Background()
	if p, err := gh.BranchProtected(ctx, "main"); err != nil || !p {
		t.Fatalf("main: protected=%v err=%v", p, err)
	}
	if p, err := gh.BranchProtected(ctx, "factory/does-not-exist"); err != nil || p {
		t.Fatalf("missing factory branch: protected=%v err=%v", p, err)
	}
}
