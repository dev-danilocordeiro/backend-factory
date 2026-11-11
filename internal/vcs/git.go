// Package vcs wraps git and the GitHub CLI.
//
// Safety rules live here, in code, not in prompts:
//   - the pipeline only ever writes to branches named factory/*;
//   - a branch reported protected (locally or by GitHub) is refused, and an
//     unknown answer from GitHub is treated as protected;
//   - push never uses --force, and there is no merge function at all.
package vcs

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strconv"
	"strings"
)

// BranchPrefix is the only namespace the pipeline writes to.
const BranchPrefix = "factory/"

// LocalProtected are branch patterns refused even if GitHub says otherwise.
var LocalProtected = []string{"main", "master", "develop", "trunk", "release/*", "release-*", "hotfix/*"}

// ErrProtected is returned for any attempt to write to a protected branch.
var ErrProtected = errors.New("refusing to write to a protected branch")

// CheckBranchName enforces the factory/* namespace and the local deny list.
func CheckBranchName(branch string) error {
	if !strings.HasPrefix(branch, BranchPrefix) || len(branch) == len(BranchPrefix) {
		return fmt.Errorf("%w: %q is outside %s*", ErrProtected, branch, BranchPrefix)
	}
	for _, p := range LocalProtected {
		if ok, _ := path.Match(p, branch); ok {
			return fmt.Errorf("%w: %q matches %q", ErrProtected, branch, p)
		}
	}
	return nil
}

// Git runs git commands in Dir.
type Git struct{ Dir string }

func (g Git) run(ctx context.Context, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, "git", args...)
	cmd.Dir = g.Dir
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("git %s: %w: %s", strings.Join(args, " "), err, strings.TrimSpace(errb.String()))
	}
	return out.String(), nil
}

// CurrentBranch returns the checked-out branch.
func (g Git) CurrentBranch(ctx context.Context) (string, error) {
	out, err := g.run(ctx, "rev-parse", "--abbrev-ref", "HEAD")
	return strings.TrimSpace(out), err
}

// AddWorktree creates dir on a new branch from base. The main checkout is
// never touched, so a run cannot disturb whatever a human has checked out.
func (g Git) AddWorktree(ctx context.Context, dir, branch, base string) (Git, error) {
	if err := CheckBranchName(branch); err != nil {
		return Git{}, err
	}
	if _, err := g.run(ctx, "worktree", "add", "-B", branch, dir, base); err != nil {
		return Git{}, err
	}
	return Git{Dir: dir}, nil
}

// RemoveWorktree deletes a worktree created by AddWorktree.
func (g Git) RemoveWorktree(ctx context.Context, dir string) error {
	_, err := g.run(ctx, "worktree", "remove", "--force", dir)
	return err
}

// StageAll stages every change and returns the staged paths.
func (g Git) StageAll(ctx context.Context) ([]string, error) {
	if _, err := g.run(ctx, "add", "-A"); err != nil {
		return nil, err
	}
	out, err := g.run(ctx, "diff", "--cached", "--name-only", "-z")
	if err != nil {
		return nil, err
	}
	return splitZ(out), nil
}

// Revert restores staged paths to HEAD, deleting files HEAD does not have.
func (g Git) Revert(ctx context.Context, paths []string) error {
	for _, p := range paths {
		if _, err := g.run(ctx, "cat-file", "-e", "HEAD:"+p); err == nil {
			if _, err := g.run(ctx, "checkout", "HEAD", "--", p); err != nil {
				return err
			}
			continue
		}
		if _, err := g.run(ctx, "rm", "-q", "--cached", "--force", "--", p); err != nil {
			return err
		}
		if err := os.Remove(filepath.Join(g.Dir, p)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	return nil
}

// Commit commits staged changes on the current branch, which must be a
// factory/* branch. It reports false when there was nothing to commit.
func (g Git) Commit(ctx context.Context, msg string) (bool, error) {
	br, err := g.CurrentBranch(ctx)
	if err != nil {
		return false, err
	}
	if err := CheckBranchName(br); err != nil {
		return false, err
	}
	if _, err := g.run(ctx, "diff", "--cached", "--quiet"); err == nil {
		return false, nil
	}
	_, err = g.run(ctx, "commit", "-q", "--no-verify", "-m", msg)
	return err == nil, err
}

// ChangedLines counts added+deleted lines between base and HEAD, ignoring
// paths under any of the excluded prefixes.
func (g Git) ChangedLines(ctx context.Context, base string, exclude ...string) (int, []string, error) {
	out, err := g.run(ctx, "diff", "--numstat", base+"...HEAD")
	if err != nil {
		return 0, nil, err
	}
	total := 0
	var files []string
	for _, line := range strings.Split(strings.TrimSpace(out), "\n") {
		f := strings.SplitN(line, "\t", 3)
		if len(f) != 3 || hasPrefix(f[2], exclude) {
			continue
		}
		a, _ := strconv.Atoi(f[0]) // "-" for binary counts as 0
		d, _ := strconv.Atoi(f[1])
		total += a + d
		files = append(files, f[2])
	}
	return total, files, nil
}

// Diff returns the patch between base and HEAD.
func (g Git) Diff(ctx context.Context, base string) (string, error) {
	return g.run(ctx, "diff", base+"...HEAD")
}

// Push pushes branch to origin after re-checking it is writable. It never
// forces: a rejected push is an error for a human to look at.
func (g Git) Push(ctx context.Context, gh GitHub, branch string) error {
	if err := CheckBranchName(branch); err != nil {
		return err
	}
	if gh != nil {
		prot, err := gh.BranchProtected(ctx, branch)
		if err != nil {
			return fmt.Errorf("%w: cannot confirm %q is unprotected: %v", ErrProtected, branch, err)
		}
		if prot {
			return fmt.Errorf("%w: GitHub reports %q protected", ErrProtected, branch)
		}
	}
	_, err := g.run(ctx, "push", "-u", "origin", branch+":"+branch)
	return err
}

func splitZ(s string) []string {
	var out []string
	for _, p := range strings.Split(s, "\x00") {
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func hasPrefix(s string, prefixes []string) bool {
	for _, p := range prefixes {
		if strings.HasPrefix(s, p) {
			return true
		}
	}
	return false
}
