package vcs

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"testing"
)

func newRepo(t *testing.T) Git {
	t.Helper()
	dir := t.TempDir()
	for _, args := range [][]string{
		{"init", "-q", "-b", "main"},
		{"config", "user.email", "t@example.com"},
		{"config", "user.name", "t"},
	} {
		if out, err := exec.Command("git", append([]string{"-C", dir}, args...)...).CombinedOutput(); err != nil {
			t.Fatalf("%v: %s", err, out)
		}
	}
	os.WriteFile(filepath.Join(dir, "keep.txt"), []byte("a\n"), 0o644)
	exec.Command("git", "-C", dir, "add", "-A").Run()
	if out, err := exec.Command("git", "-C", dir, "commit", "-qm", "init").CombinedOutput(); err != nil {
		t.Fatalf("%v: %s", err, out)
	}
	return Git{Dir: dir}
}

func TestCheckBranchName(t *testing.T) {
	for _, b := range []string{"main", "master", "feature/x", "factory/", "release/1.0"} {
		if err := CheckBranchName(b); !errors.Is(err, ErrProtected) {
			t.Errorf("%q must be refused", b)
		}
	}
	if err := CheckBranchName("factory/12-add-health"); err != nil {
		t.Error(err)
	}
}

func TestCommitRefusesProtectedBranch(t *testing.T) {
	g := newRepo(t)
	ctx := context.Background()
	os.WriteFile(filepath.Join(g.Dir, "new.txt"), []byte("x\n"), 0o644)
	if _, err := g.StageAll(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := g.Commit(ctx, "nope"); !errors.Is(err, ErrProtected) {
		t.Fatalf("commit on main must be refused, got %v", err)
	}
}

func TestWorktreeCommitRevertAndDiffSize(t *testing.T) {
	g := newRepo(t)
	ctx := context.Background()
	wt, err := g.AddWorktree(ctx, filepath.Join(t.TempDir(), "wt"), "factory/1-x", "main")
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(wt.Dir, "keep.txt"), []byte("changed\n"), 0o644)
	os.WriteFile(filepath.Join(wt.Dir, "ok.txt"), []byte("1\n2\n"), 0o644)
	os.MkdirAll(filepath.Join(wt.Dir, ".factory/1"), 0o755)
	os.WriteFile(filepath.Join(wt.Dir, ".factory/1/spec.md"), []byte("s\ns\ns\n"), 0o644)

	staged, err := wt.StageAll(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(staged, []string{".factory/1/spec.md", "keep.txt", "ok.txt"}) {
		t.Fatalf("staged %v", staged)
	}
	// keep.txt is "out of scope": restore it to HEAD.
	if err := wt.Revert(ctx, []string{"keep.txt"}); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(wt.Dir, "keep.txt")); string(b) != "a\n" {
		t.Fatalf("keep.txt not restored: %q", b)
	}
	if ok, err := wt.Commit(ctx, "stage"); err != nil || !ok {
		t.Fatalf("commit: %v %v", ok, err)
	}
	n, files, err := wt.ChangedLines(ctx, "main", ".factory/")
	if err != nil || n != 2 || !slices.Equal(files, []string{"ok.txt"}) {
		t.Fatalf("lines %d files %v err %v", n, files, err)
	}
	// Reverting a file that HEAD never had deletes it.
	os.WriteFile(filepath.Join(wt.Dir, "rogue.txt"), []byte("r\n"), 0o644)
	wt.StageAll(ctx)
	if err := wt.Revert(ctx, []string{"rogue.txt"}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(wt.Dir, "rogue.txt")); !os.IsNotExist(err) {
		t.Fatal("rogue.txt should be gone")
	}
	if err := g.RemoveWorktree(ctx, wt.Dir); err != nil {
		t.Fatal(err)
	}
}

type fakeGH struct {
	GitHub
	protected bool
	err       error
}

func (f fakeGH) BranchProtected(context.Context, string) (bool, error) { return f.protected, f.err }

func TestPushFailsClosed(t *testing.T) {
	g := newRepo(t)
	ctx := context.Background()
	if err := g.Push(ctx, fakeGH{protected: true}, "factory/1-x"); !errors.Is(err, ErrProtected) {
		t.Fatalf("protected: %v", err)
	}
	if err := g.Push(ctx, fakeGH{err: errors.New("403")}, "factory/1-x"); !errors.Is(err, ErrProtected) {
		t.Fatalf("unknown must fail closed: %v", err)
	}
	if err := g.Push(ctx, nil, "main"); !errors.Is(err, ErrProtected) {
		t.Fatalf("main: %v", err)
	}
}
