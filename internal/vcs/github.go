package vcs

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
)

// Issue is the input to a run.
type Issue struct {
	ID     string   `json:"id"`
	Source string   `json:"source"` // github | linear
	Title  string   `json:"title"`
	Body   string   `json:"body"`
	Labels []string `json:"labels"`
	URL    string   `json:"url,omitempty"`
}

// PR is a pull request to open.
type PR struct {
	Base, Head, Title, Body string
	Draft                   bool
}

// GitHub is the subset of GitHub the pipeline uses. Note what is missing:
// there is no merge, no branch-protection change, and no force push.
type GitHub interface {
	Issue(ctx context.Context, number string) (Issue, error)
	BranchProtected(ctx context.Context, branch string) (bool, error)
	CreatePR(ctx context.Context, pr PR) (string, error)
	Comment(ctx context.Context, issue, body string) error
	AddLabel(ctx context.Context, issue, label string) error
	PRMerged(ctx context.Context, url string) (bool, error)
}

// GH implements GitHub with the gh CLI.
type GH struct {
	Repo string // owner/name
	Dir  string
}

func (g GH) gh(ctx context.Context, stdin []byte, args ...string) ([]byte, error) {
	if g.Repo != "" && args[0] != "api" {
		args = append(args, "--repo", g.Repo)
	}
	cmd := exec.CommandContext(ctx, "gh", args...)
	cmd.Dir = g.Dir
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	if err := cmd.Run(); err != nil {
		return out.Bytes(), fmt.Errorf("gh %s: %w: %s", args[0], err, strings.TrimSpace(errb.String()))
	}
	return out.Bytes(), nil
}

// Issue fetches a GitHub issue.
func (g GH) Issue(ctx context.Context, number string) (Issue, error) {
	out, err := g.gh(ctx, nil, "issue", "view", number, "--json", "number,title,body,labels,url")
	if err != nil {
		return Issue{}, err
	}
	var raw struct {
		Number int    `json:"number"`
		Title  string `json:"title"`
		Body   string `json:"body"`
		URL    string `json:"url"`
		Labels []struct {
			Name string `json:"name"`
		} `json:"labels"`
	}
	if err := json.Unmarshal(out, &raw); err != nil {
		return Issue{}, err
	}
	is := Issue{ID: fmt.Sprint(raw.Number), Source: "github", Title: raw.Title, Body: raw.Body, URL: raw.URL}
	for _, l := range raw.Labels {
		is.Labels = append(is.Labels, l.Name)
	}
	return is, nil
}

// BranchProtected asks GitHub. A branch that does not exist remotely yet is
// not protected; any other error is returned so callers fail closed.
func (g GH) BranchProtected(ctx context.Context, branch string) (bool, error) {
	out, err := g.gh(ctx, nil, "api", fmt.Sprintf("repos/%s/branches/%s", g.Repo, branch), "--jq", ".protected")
	if err != nil {
		if strings.Contains(err.Error(), "Branch not found") || strings.Contains(err.Error(), "HTTP 404") {
			return false, nil
		}
		return true, err
	}
	return strings.TrimSpace(string(out)) == "true", nil
}

// CreatePR opens a pull request and returns its URL.
func (g GH) CreatePR(ctx context.Context, pr PR) (string, error) {
	if err := CheckBranchName(pr.Head); err != nil {
		return "", err
	}
	args := []string{"pr", "create", "--base", pr.Base, "--head", pr.Head, "--title", pr.Title, "--body-file", "-"}
	if pr.Draft {
		args = append(args, "--draft")
	}
	out, err := g.gh(ctx, []byte(pr.Body), args...)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

// Comment posts a comment on an issue.
func (g GH) Comment(ctx context.Context, issue, body string) error {
	_, err := g.gh(ctx, []byte(body), "issue", "comment", issue, "--body-file", "-")
	return err
}

// AddLabel adds a label to an issue.
func (g GH) AddLabel(ctx context.Context, issue, label string) error {
	_, err := g.gh(ctx, nil, "issue", "edit", issue, "--add-label", label)
	return err
}

// PRMerged reports whether a PR was merged (by a human; the pipeline cannot).
func (g GH) PRMerged(ctx context.Context, url string) (bool, error) {
	out, err := g.gh(ctx, nil, "pr", "view", url, "--json", "state", "--jq", ".state")
	if err != nil {
		return false, err
	}
	return strings.TrimSpace(string(out)) == "MERGED", nil
}
