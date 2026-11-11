// Package stage runs one pipeline stage as a headless Claude Code session.
//
// Each stage is a separate `claude -p --agent <name>` process, so every stage
// starts with a clean context and only the tools its agent definition and
// the allowlist grant. The orchestrator, not the model, decides what runs next.
package stage

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// Request describes one stage invocation.
type Request struct {
	Agent        string   // name in .claude/agents/
	Prompt       string   // user turn
	AllowedTools []string // pre-approved permission rules
	MaxTurns     int
	MaxBudgetUSD float64
	Model        string // optional
	Dir          string // working directory (repo root of the run worktree)
	Env          []string
	Timeout      time.Duration
}

// Result is the parsed `--output-format json` result plus process facts.
type Result struct {
	Text              string            `json:"result"`
	SessionID         string            `json:"session_id"`
	Subtype           string            `json:"subtype"`
	IsError           bool              `json:"is_error"`
	NumTurns          int               `json:"num_turns"`
	TotalCostUSD      float64           `json:"total_cost_usd"`
	DurationMS        int64             `json:"duration_ms"`
	PermissionDenials []json.RawMessage `json:"permission_denials"`
	Errors            []string          `json:"errors"`
	ExitCode          int               `json:"-"`
	Stderr            string            `json:"-"`
}

// Failed reports whether the session did not end in success.
func (r *Result) Failed() bool {
	return r.IsError || (r.Subtype != "" && r.Subtype != "success")
}

// Runner executes a stage.
type Runner interface {
	Run(ctx context.Context, req Request) (*Result, error)
}

// Claude runs the real Claude Code CLI.
type Claude struct {
	Bin string // defaults to "claude"
}

// Args builds the CLI arguments for req. Exported for tests and dry runs.
func Args(req Request) []string {
	args := []string{
		"-p", req.Prompt,
		"--agent", req.Agent,
		"--output-format", "json",
		// Nothing in a pipeline can answer a permission prompt: deny instead of waiting.
		"--permission-prompts", "none",
	}
	if req.MaxTurns > 0 {
		args = append(args, "--max-turns", strconv.Itoa(req.MaxTurns))
	}
	if req.MaxBudgetUSD > 0 {
		args = append(args, "--max-budget-usd", strconv.FormatFloat(req.MaxBudgetUSD, 'f', 2, 64))
	}
	if req.Model != "" {
		args = append(args, "--model", req.Model)
	}
	if len(req.AllowedTools) > 0 {
		args = append(args, "--allowedTools", strings.Join(req.AllowedTools, ","))
	}
	return args
}

// Run executes the CLI and parses its JSON result.
func (c Claude) Run(ctx context.Context, req Request) (*Result, error) {
	bin := c.Bin
	if bin == "" {
		bin = "claude"
	}
	if req.Timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, req.Timeout)
		defer cancel()
	}
	cmd := exec.CommandContext(ctx, bin, Args(req)...)
	cmd.Dir = req.Dir
	cmd.Env = append(os.Environ(), req.Env...)
	// SIGINT first so the turn ends cleanly; the docs note SIGTERM leaves it unrecorded.
	cmd.Cancel = func() error { return cmd.Process.Signal(os.Interrupt) }
	cmd.WaitDelay = 30 * time.Second
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	runErr := cmd.Run()

	res, parseErr := Parse(stdout.Bytes())
	if res == nil {
		res = &Result{}
	}
	res.Stderr = tail(stderr.String(), 4000)
	var exitErr *exec.ExitError
	if errors.As(runErr, &exitErr) {
		res.ExitCode = exitErr.ExitCode()
	}
	if ctx.Err() != nil {
		return res, fmt.Errorf("stage %s: %w", req.Agent, ctx.Err())
	}
	if parseErr != nil {
		return res, fmt.Errorf("stage %s: exit %d: %w; stderr: %s", req.Agent, res.ExitCode, parseErr, res.Stderr)
	}
	return res, nil
}

// Parse decodes the final JSON result. It accepts a single object or, to be
// tolerant of stray lines, takes the last line that decodes as a result.
func Parse(out []byte) (*Result, error) {
	out = bytes.TrimSpace(out)
	if len(out) == 0 {
		return nil, errors.New("empty output")
	}
	var r Result
	if err := json.Unmarshal(out, &r); err == nil {
		return &r, nil
	}
	lines := bytes.Split(out, []byte("\n"))
	for i := len(lines) - 1; i >= 0; i-- {
		var probe struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(lines[i], &probe) == nil && probe.Type == "result" {
			if err := json.Unmarshal(lines[i], &r); err == nil {
				return &r, nil
			}
		}
	}
	return nil, fmt.Errorf("no JSON result in output: %q", tail(string(out), 300))
}

func tail(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return "…" + s[len(s)-n:]
}
