// Package pipeline drives one issue through spec → plan → implement → test →
// gates → review → pull request. The sequence, retries and stop conditions are
// plain Go; the model only ever works inside one stage at a time.
package pipeline

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/dev-danilocordeiro/backend-factory/internal/gates"
	"github.com/dev-danilocordeiro/backend-factory/internal/metrics"
	"github.com/dev-danilocordeiro/backend-factory/internal/stage"
	"github.com/dev-danilocordeiro/backend-factory/internal/state"
	"github.com/dev-danilocordeiro/backend-factory/internal/vcs"
)

// Limits bound one stage session.
type Limits struct {
	MaxTurns     int
	MaxBudgetUSD float64
	Timeout      time.Duration
}

// Config is everything a run needs that is not the issue itself.
type Config struct {
	RepoDir         string // main checkout; never modified by a run
	WorkRoot        string // parent dir for per-issue worktrees
	Base            string // branch PRs target
	Service         string // service path relative to repo root, e.g. examples/go-service
	MaxAttempts     int    // implement attempts before asking a human
	MaxDiffLines    int    // changed lines outside .factory/ allowed in one PR
	Model           string
	Limits          map[state.Stage]Limits
	NeedsHumanLabel string
	Remote          bool // push branches, open PRs, comment on issues
}

// DefaultLimits are conservative per-stage caps.
func DefaultLimits() map[state.Stage]Limits {
	return map[state.Stage]Limits{
		state.StageSpec:      {MaxTurns: 20, MaxBudgetUSD: 2, Timeout: 15 * time.Minute},
		state.StagePlan:      {MaxTurns: 25, MaxBudgetUSD: 2, Timeout: 15 * time.Minute},
		state.StageImplement: {MaxTurns: 60, MaxBudgetUSD: 5, Timeout: 30 * time.Minute},
		state.StageTest:      {MaxTurns: 40, MaxBudgetUSD: 3, Timeout: 20 * time.Minute},
		state.StageReview:    {MaxTurns: 25, MaxBudgetUSD: 2, Timeout: 15 * time.Minute},
	}
}

// Pipeline wires the collaborators. All of them are interfaces or plain
// structs so tests can replace the model and GitHub.
type Pipeline struct {
	Cfg     Config
	Runner  stage.Runner
	Gates   gates.Runner
	GitHub  vcs.GitHub // nil when Remote is false
	Metrics *metrics.Recorder
	Log     func(format string, args ...any)
}

// run holds per-issue working values.
type run struct {
	issue     vcs.Issue
	st        *state.State
	wt        vcs.Git
	stack     gates.Stack
	artRel    string // .factory/<id>, relative to the worktree
	artAbs    string
	pc        promptCtx
	gateRep   gates.Report
	reviewMD  string
	lastFail  state.Stage
	lastNote  string
	startedAt time.Time
}

// Run processes one issue to a PR or to a needs-human stop. A non-nil error
// means the pipeline itself failed (git, gh, process), not the change.
func (p *Pipeline) Run(ctx context.Context, issue vcs.Issue) (*state.State, error) {
	p.event(metrics.Event{Kind: metrics.KindReceived, Issue: issue.ID, Reason: issue.Title})
	r := &run{issue: issue, startedAt: time.Now()}
	branch := fmt.Sprintf("%s%s-%s", vcs.BranchPrefix, issue.ID, slug(issue.Title))
	r.st = &state.State{Issue: issue.ID, Source: issue.Source, Title: issue.Title, Service: p.Cfg.Service,
		Base: p.Cfg.Base, Branch: branch, Status: state.StatusRunning}

	err := p.run(ctx, r)
	if err != nil {
		r.st.Status = state.StatusError
		r.st.Reason = err.Error()
		p.event(metrics.Event{Kind: metrics.KindRunError, Issue: issue.ID, Stage: string(r.st.Stage), Reason: err.Error()})
	}
	if r.artAbs != "" {
		if serr := r.st.Save(r.artAbs); serr != nil && err == nil {
			err = serr
		}
	}
	return r.st, err
}

func (p *Pipeline) run(ctx context.Context, r *run) error {
	main := vcs.Git{Dir: p.Cfg.RepoDir}
	wtDir := filepath.Join(p.Cfg.WorkRoot, r.issue.ID)
	if _, err := os.Stat(wtDir); err == nil {
		_ = main.RemoveWorktree(ctx, wtDir)
		_ = os.RemoveAll(wtDir)
	}
	wt, err := main.AddWorktree(ctx, wtDir, r.st.Branch, p.Cfg.Base)
	if err != nil {
		return err
	}
	r.wt = wt
	r.artRel = filepath.ToSlash(filepath.Join(".factory", r.issue.ID))
	r.artAbs = filepath.Join(wt.Dir, r.artRel)
	if err := os.MkdirAll(r.artAbs, 0o755); err != nil {
		return err
	}
	if r.stack, err = gates.Detect(filepath.Join(wt.Dir, p.Cfg.Service)); err != nil {
		return err
	}
	r.pc = promptCtx{Issue: r.issue.ID, Service: p.Cfg.Service, Stack: string(r.stack), Artifacts: r.artRel,
		Base: p.Cfg.Base, MaxAttempts: p.Cfg.MaxAttempts}
	if err := p.write(r, "issue.md", fmt.Sprintf("# %s\n\nSource: %s %s\n\n%s\n", r.issue.Title, r.issue.Source, r.issue.URL, r.issue.Body)); err != nil {
		return err
	}
	// Everything the orchestrator writes is committed before an agent runs,
	// so the scope check after the agent only sees the agent's own changes.
	if err := p.commit(ctx, r, "issue"); err != nil {
		return err
	}

	// spec
	text, ok, err := p.stage(ctx, r, state.StageSpec, specPrompt(r.pc), gates.ReadOnlyScope())
	if err != nil || !ok {
		return p.stopIf(ctx, r, err, state.StageSpec, "spec session failed")
	}
	if lastMatch(statusRe, text) != "READY" {
		return p.stop(ctx, r, state.StageSpec, "spec agent marked the issue NEEDS_HUMAN (or gave no READY marker)")
	}

	// plan
	if _, ok, err = p.stage(ctx, r, state.StagePlan, planPrompt(r.pc), gates.ReadOnlyScope()); err != nil || !ok {
		return p.stopIf(ctx, r, err, state.StagePlan, "plan session failed")
	}

	// implement → test → gates → review, at most MaxAttempts times
	for r.st.Attempt = 1; r.st.Attempt <= p.Cfg.MaxAttempts; r.st.Attempt++ {
		done, halt, err := p.attempt(ctx, r)
		if err != nil {
			return err
		}
		if done {
			return p.openPR(ctx, r)
		}
		if halt {
			return p.stop(ctx, r, r.lastFail, r.lastNote)
		}
	}
	r.st.Attempt = p.Cfg.MaxAttempts
	return p.stop(ctx, r, r.lastFail, fmt.Sprintf("still red after %d attempts: %s", p.Cfg.MaxAttempts, r.lastNote))
}

// attempt runs one implement cycle. done means everything is green; halt
// means retrying cannot help and a human is needed now.
func (p *Pipeline) attempt(ctx context.Context, r *run) (done, halt bool, err error) {
	pc := r.pc
	pc.Attempt = r.st.Attempt
	pc.HasFeedback = r.st.Attempt > 1
	fail := func(st state.Stage, note string) (bool, bool, error) {
		r.lastFail, r.lastNote = st, firstLine(note)
		if err := p.write(r, "feedback.md", fmt.Sprintf("# Rejected at %s (attempt %d)\n\n%s\n", st, r.st.Attempt, note)); err != nil {
			return false, false, err
		}
		return false, false, p.commit(ctx, r, "feedback")
	}

	if _, ok, err := p.stage(ctx, r, state.StageImplement, implementPrompt(pc), gates.ImplementScope(p.Cfg.Service)); err != nil {
		return false, false, err
	} else if !ok {
		return fail(state.StageImplement, r.lastNote)
	}
	if _, ok, err := p.stage(ctx, r, state.StageTest, testPrompt(pc), gates.TestScope(p.Cfg.Service, r.stack)); err != nil {
		return false, false, err
	} else if !ok {
		return fail(state.StageTest, r.lastNote)
	}

	rep, err := p.Gates.Run(ctx, filepath.Join(r.wt.Dir, p.Cfg.Service))
	if err != nil {
		return false, false, err
	}
	r.gateRep = rep
	b, _ := json.MarshalIndent(rep, "", "  ")
	if err := p.write(r, "gates.json", string(b)+"\n"); err != nil {
		return false, false, err
	}
	if err := p.commit(ctx, r, "gates"); err != nil {
		return false, false, err
	}
	okp := rep.Green()
	r.st.Record(state.StageGates, okp, "")
	p.event(metrics.Event{Kind: metrics.KindStage, Issue: r.issue.ID, Stage: "gates", Attempt: r.st.Attempt, OK: &okp})
	if !okp {
		red := rep.FirstRed()
		return fail(state.StageGates, fmt.Sprintf("Gate `%s` failed (exit %d). Command: `%s`\n\n```\n%s\n```", red.Gate, red.ExitCode, red.Command, red.Output))
	}

	lines, _, err := r.wt.ChangedLines(ctx, p.Cfg.Base, ".factory/")
	if err != nil {
		return false, false, err
	}
	if lines > p.Cfg.MaxDiffLines {
		// A PR this size is not "small"; retrying will not shrink the issue.
		r.lastFail, r.lastNote = state.StageGates, fmt.Sprintf("diff has %d changed lines, cap is %d; split the issue", lines, p.Cfg.MaxDiffLines)
		return false, true, nil
	}

	text, ok, err := p.stage(ctx, r, state.StageReview, reviewPrompt(pc), gates.ReadOnlyScope())
	if err != nil {
		return false, false, err
	}
	if !ok {
		return fail(state.StageReview, r.lastNote)
	}
	r.reviewMD = text
	if lastMatch(verdictRe, text) != "APPROVE" {
		return fail(state.StageReview, "The reviewer requested changes:\n\n"+text)
	}
	return true, false, nil
}

// stage runs one agent session, enforces its scope and commits the result.
// ok is false when the session failed or touched files outside its scope.
func (p *Pipeline) stage(ctx context.Context, r *run, st state.Stage, prompt string, scope gates.Scope) (string, bool, error) {
	r.st.Stage = st
	lim := p.Cfg.Limits[st]
	p.logf("issue %s: %s (attempt %d)", r.issue.ID, st, r.st.Attempt)
	res, runErr := p.Runner.Run(ctx, stage.Request{
		Agent: string(st), Prompt: prompt, AllowedTools: allowedTools(st, r.stack),
		MaxTurns: lim.MaxTurns, MaxBudgetUSD: lim.MaxBudgetUSD, Timeout: lim.Timeout, Model: p.Cfg.Model, Dir: r.wt.Dir,
		Env: []string{"FACTORY_STAGE=" + string(st), "FACTORY_SERVICE=" + p.Cfg.Service, "FACTORY_STACK=" + string(r.stack), "FACTORY_ISSUE=" + r.issue.ID},
	})
	if res == nil {
		res = &stage.Result{}
	}
	r.st.CostUSD += res.TotalCostUSD

	note := ""
	ok := runErr == nil && !res.Failed()
	switch {
	case runErr != nil:
		note = runErr.Error()
	case res.Failed():
		note = fmt.Sprintf("session ended with %s: %s", res.Subtype, strings.Join(res.Errors, "; "))
	}

	// Scope: revert anything the stage was not allowed to touch. The
	// orchestrator writes the artifacts, so the agent never needs .factory/.
	staged, err := r.wt.StageAll(ctx)
	if err != nil {
		return "", false, err
	}
	if bad := scope.Violations(staged); len(bad) > 0 {
		if err := r.wt.Revert(ctx, bad); err != nil {
			return "", false, err
		}
		ok = false
		note = fmt.Sprintf("changed files outside its scope (reverted): %s", strings.Join(bad, ", "))
	}
	if res.Text != "" {
		if err := p.write(r, string(st)+".md", res.Text+"\n"); err != nil {
			return "", false, err
		}
	}
	if err := p.commit(ctx, r, string(st)); err != nil {
		return "", false, err
	}

	r.st.Record(st, ok, note)
	r.lastNote = note
	p.event(metrics.Event{Kind: metrics.KindStage, Issue: r.issue.ID, Stage: string(st), Attempt: r.st.Attempt, OK: &ok,
		Turns: res.NumTurns, CostUSD: res.TotalCostUSD, DurationMS: res.DurationMS, Reason: note})
	if ctx.Err() != nil {
		return res.Text, false, ctx.Err()
	}
	return res.Text, ok, nil
}

func (p *Pipeline) commit(ctx context.Context, r *run, what string) error {
	if _, err := r.wt.StageAll(ctx); err != nil {
		return err
	}
	_, err := r.wt.Commit(ctx, fmt.Sprintf("factory(%s): %s, attempt %d", r.issue.ID, what, r.st.Attempt))
	return err
}

func (p *Pipeline) stopIf(ctx context.Context, r *run, err error, st state.Stage, reason string) error {
	if err != nil && !errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	if r.lastNote != "" {
		reason += ": " + firstLine(r.lastNote)
	}
	return p.stop(ctx, r, st, reason)
}

// stop ends the run and hands it to a human.
func (p *Pipeline) stop(ctx context.Context, r *run, st state.Stage, reason string) error {
	r.st.Stop(st, reason)
	p.event(metrics.Event{Kind: metrics.KindStopped, Issue: r.issue.ID, Stage: string(st), Attempt: r.st.Attempt, Reason: reason})
	p.logf("issue %s: needs human at %s: %s", r.issue.ID, st, reason)
	if err := r.st.Save(r.artAbs); err != nil {
		return err
	}
	if err := p.commit(ctx, r, "stopped"); err != nil {
		return err
	}
	if !p.Cfg.Remote || p.GitHub == nil || r.issue.Source != "github" {
		return nil
	}
	// Push the factory/* branch so a human can pick up the artifacts.
	pushed := r.wt.Push(ctx, p.GitHub, r.st.Branch) == nil
	body := fmt.Sprintf("backend-factory stopped at **%s** and needs a human.\n\nReason: %s\n", st, reason)
	if pushed {
		body += fmt.Sprintf("\nArtifacts: branch `%s`, folder `%s/`.\n", r.st.Branch, r.artRel)
	}
	if err := p.GitHub.Comment(ctx, r.issue.ID, body); err != nil {
		return err
	}
	return p.GitHub.AddLabel(ctx, r.issue.ID, p.Cfg.NeedsHumanLabel)
}

func (p *Pipeline) openPR(ctx context.Context, r *run) error {
	r.st.Stage = state.StagePR
	r.st.Status = state.StatusPROpened
	lines, files, err := r.wt.ChangedLines(ctx, p.Cfg.Base, ".factory/")
	if err != nil {
		return err
	}
	body := prBody(r, p.Cfg, lines, files)
	if err := p.write(r, "pr.md", body); err != nil {
		return err
	}
	if err := r.st.Save(r.artAbs); err != nil {
		return err
	}
	if err := p.commit(ctx, r, "pr"); err != nil {
		return err
	}
	if !p.Cfg.Remote || p.GitHub == nil {
		p.logf("issue %s: green; remote disabled, PR body at %s/pr.md", r.issue.ID, r.artAbs)
		p.event(metrics.Event{Kind: metrics.KindPROpened, Issue: r.issue.ID, Reason: "local only"})
		return nil
	}
	if err := r.wt.Push(ctx, p.GitHub, r.st.Branch); err != nil {
		return err
	}
	url, err := p.GitHub.CreatePR(ctx, vcs.PR{Base: p.Cfg.Base, Head: r.st.Branch, Title: r.issue.Title, Body: body})
	if err != nil {
		return err
	}
	r.st.PRURL = url
	p.event(metrics.Event{Kind: metrics.KindPROpened, Issue: r.issue.ID, PRURL: url})
	p.logf("issue %s: opened %s", r.issue.ID, url)
	return nil
}

func (p *Pipeline) write(r *run, name, content string) error {
	return os.WriteFile(filepath.Join(r.artAbs, name), []byte(content), 0o644)
}

func (p *Pipeline) event(e metrics.Event) {
	if p.Metrics == nil {
		return
	}
	if err := p.Metrics.Record(e); err != nil {
		p.logf("metrics: %v", err)
	}
}

func (p *Pipeline) logf(format string, args ...any) {
	if p.Log != nil {
		p.Log(format, args...)
	}
}

func firstLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}
	if len(s) > 200 {
		s = s[:200] + "…"
	}
	return s
}
