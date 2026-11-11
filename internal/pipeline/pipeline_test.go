package pipeline

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dev-danilocordeiro/backend-factory/internal/gates"
	"github.com/dev-danilocordeiro/backend-factory/internal/metrics"
	"github.com/dev-danilocordeiro/backend-factory/internal/stage"
	"github.com/dev-danilocordeiro/backend-factory/internal/state"
	"github.com/dev-danilocordeiro/backend-factory/internal/vcs"
)

const svc = "examples/svc"

// fakeModel plays each agent with a scripted function.
type fakeModel struct {
	calls map[string]int
	act   map[string]func(dir string, n int) string
}

func (f *fakeModel) Run(_ context.Context, req stage.Request) (*stage.Result, error) {
	f.calls[req.Agent]++
	text := "ok"
	if fn := f.act[req.Agent]; fn != nil {
		text = fn(req.Dir, f.calls[req.Agent])
	}
	return &stage.Result{Text: text, Subtype: "success", NumTurns: 2, TotalCostUSD: 0.01}, nil
}

func happyModel() *fakeModel {
	return &fakeModel{calls: map[string]int{}, act: map[string]func(string, int) string{
		"spec": func(string, int) string { return "## Summary\n\nAdd a thing.\n\nSTATUS: READY" },
		"implement": func(dir string, n int) string {
			writeFile(dir, svc+"/thing.go", "package svc\n")
			return "implemented"
		},
		"test": func(dir string, n int) string {
			writeFile(dir, svc+"/thing_test.go", "package svc\n")
			return "tested"
		},
		"review": func(string, int) string {
			return "## Findings\n\nnone\n\n## Risks\n\n- small risk\n\nVERDICT: APPROVE"
		},
	}}
}

func writeFile(root, rel, content string) {
	p := filepath.Join(root, rel)
	os.MkdirAll(filepath.Dir(p), 0o755)
	os.WriteFile(p, []byte(content), 0o644)
}

func git(t *testing.T, dir string, args ...string) string {
	t.Helper()
	out, err := exec.Command("git", append([]string{"-C", dir}, args...)...).CombinedOutput()
	if err != nil {
		t.Fatalf("git %v: %v: %s", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

// setup returns a pipeline over a fresh repo and a gate script whose
// behaviour is given as shell.
func setup(t *testing.T, model *fakeModel, gateScript string) (*Pipeline, string) {
	t.Helper()
	repo := t.TempDir()
	git(t, repo, "init", "-q", "-b", "main")
	git(t, repo, "config", "user.email", "t@example.com")
	git(t, repo, "config", "user.name", "t")
	writeFile(repo, svc+"/go.mod", "module svc\n")
	writeFile(repo, "gates/go.sh", "#!/bin/sh\n")
	git(t, repo, "add", "-A")
	git(t, repo, "commit", "-qm", "init")

	scripts := t.TempDir()
	os.WriteFile(filepath.Join(scripts, "go.sh"), []byte("#!/bin/sh\n"+gateScript+"\n"), 0o755)
	metricsPath := filepath.Join(t.TempDir(), "m.jsonl")
	return &Pipeline{
		Cfg: Config{RepoDir: repo, WorkRoot: t.TempDir(), Base: "main", Service: svc, MaxAttempts: 3,
			MaxDiffLines: 100, Limits: DefaultLimits(), NeedsHumanLabel: "factory:needs-human"},
		Runner:  model,
		Gates:   gates.Runner{ScriptsDir: scripts},
		Metrics: metrics.NewRecorder(metricsPath),
	}, metricsPath
}

func summary(t *testing.T, path string) metrics.Summary {
	evs, err := metrics.Read(path)
	if err != nil {
		t.Fatal(err)
	}
	return metrics.Summarize(evs)
}

func artifact(t *testing.T, p *Pipeline, id, name string) string {
	b, err := os.ReadFile(filepath.Join(p.Cfg.WorkRoot, id, ".factory", id, name))
	if err != nil {
		t.Fatalf("artifact %s: %v", name, err)
	}
	return string(b)
}

func TestHappyPath(t *testing.T) {
	m := happyModel()
	p, mp := setup(t, m, "exit 0")
	mainHead := git(t, p.Cfg.RepoDir, "rev-parse", "main")

	st, err := p.Run(context.Background(), vcs.Issue{ID: "1", Source: "github", Title: "Add a thing"})
	if err != nil {
		t.Fatal(err)
	}
	if st.Status != state.StatusPROpened || st.Attempt != 1 || st.Branch != "factory/1-add-a-thing" {
		t.Fatalf("state: %+v", st)
	}
	for _, name := range []string{"issue.md", "spec.md", "plan.md", "implement.md", "test.md", "gates.json", "review.md", "pr.md", "state.json"} {
		artifact(t, p, "1", name)
	}
	body := artifact(t, p, "1", "pr.md")
	for _, want := range []string{"## Verified", "## Not verified", "## Risks", "- small risk", "Closes #1", "**test**"} {
		if !strings.Contains(body, want) {
			t.Errorf("PR body missing %q:\n%s", want, body)
		}
	}
	if got := git(t, p.Cfg.RepoDir, "rev-parse", "main"); got != mainHead {
		t.Fatal("main moved")
	}
	if git(t, p.Cfg.RepoDir, "branch", "--show-current") != "main" {
		t.Fatal("main checkout switched branch")
	}
	s := summary(t, mp)
	if s.IssuesReceived != 1 || s.PRsOpened != 1 || s.PRsAccepted != 0 {
		t.Fatalf("metrics: %+v", s)
	}
}

func TestGateRedStopsAfterThreeAttempts(t *testing.T) {
	m := happyModel()
	p, mp := setup(t, m, `if [ "$1" = test ]; then echo "FAIL TestThing"; exit 1; fi`)
	st, err := p.Run(context.Background(), vcs.Issue{ID: "2", Source: "github", Title: "x"})
	if err != nil {
		t.Fatal(err)
	}
	if st.Status != state.StatusNeedsHuman || st.FailedStage != state.StageGates || st.Attempt != 3 {
		t.Fatalf("state: %+v", st)
	}
	if m.calls["implement"] != 3 || m.calls["review"] != 0 {
		t.Fatalf("calls: %v", m.calls)
	}
	if fb := artifact(t, p, "2", "feedback.md"); !strings.Contains(fb, "FAIL TestThing") {
		t.Fatalf("feedback: %s", fb)
	}
	if s := summary(t, mp); s.StoppedAt["gates"] != 1 || s.PRsOpened != 0 {
		t.Fatalf("metrics: %+v", s)
	}
}

func TestSpecNeedsHuman(t *testing.T) {
	m := happyModel()
	m.act["spec"] = func(string, int) string { return "Too vague.\n\nSTATUS: NEEDS_HUMAN" }
	p, _ := setup(t, m, "exit 0")
	st, err := p.Run(context.Background(), vcs.Issue{ID: "3", Source: "github", Title: "make it faster"})
	if err != nil {
		t.Fatal(err)
	}
	if st.FailedStage != state.StageSpec || m.calls["plan"] != 0 || m.calls["implement"] != 0 {
		t.Fatalf("state %+v calls %v", st, m.calls)
	}
}

func TestOutOfScopeEditIsRevertedAndRetried(t *testing.T) {
	m := happyModel()
	m.act["implement"] = func(dir string, n int) string {
		if n == 1 {
			writeFile(dir, "gates/go.sh", "#!/bin/sh\nexit 0\n") // tries to weaken a gate
		}
		writeFile(dir, svc+"/thing.go", "package svc\n")
		return "done"
	}
	p, _ := setup(t, m, "exit 0")
	st, err := p.Run(context.Background(), vcs.Issue{ID: "4", Source: "github", Title: "x"})
	if err != nil {
		t.Fatal(err)
	}
	if st.Status != state.StatusPROpened || st.Attempt != 2 {
		t.Fatalf("state: %+v", st)
	}
	wt := filepath.Join(p.Cfg.WorkRoot, "4")
	if git(t, wt, "diff", "main...HEAD", "--", "gates") != "" {
		t.Fatal("gate script change leaked into the branch")
	}
	if fb := artifact(t, p, "4", "feedback.md"); !strings.Contains(fb, "gates/go.sh") {
		t.Fatalf("feedback: %s", fb)
	}
}

func TestReviewRequestChangesLoopsBack(t *testing.T) {
	m := happyModel()
	m.act["review"] = func(_ string, n int) string {
		if n == 1 {
			return "## Findings\n\n- missing nil check\n\nVERDICT: REQUEST_CHANGES"
		}
		return "VERDICT: APPROVE"
	}
	p, _ := setup(t, m, "exit 0")
	st, err := p.Run(context.Background(), vcs.Issue{ID: "5", Source: "github", Title: "x"})
	if err != nil {
		t.Fatal(err)
	}
	if st.Status != state.StatusPROpened || m.calls["implement"] != 2 {
		t.Fatalf("state %+v calls %v", st, m.calls)
	}
	if !strings.Contains(artifact(t, p, "5", "pr.md"), "Treat that as missing information") {
		t.Fatal("PR must say risks are missing when the reviewer lists none")
	}
}

func TestOversizedDiffHaltsWithoutRetry(t *testing.T) {
	m := happyModel()
	m.act["implement"] = func(dir string, n int) string {
		writeFile(dir, svc+"/big.go", strings.Repeat("// line\n", 500))
		return "done"
	}
	p, _ := setup(t, m, "exit 0")
	st, err := p.Run(context.Background(), vcs.Issue{ID: "6", Source: "github", Title: "x"})
	if err != nil {
		t.Fatal(err)
	}
	if st.Status != state.StatusNeedsHuman || m.calls["implement"] != 1 || !strings.Contains(st.Reason, "cap is 100") {
		t.Fatalf("state %+v calls %v", st, m.calls)
	}
}

func TestSectionDropsMarkers(t *testing.T) {
	md := "## Risks\n\n- one\n\nVERDICT: APPROVE\n"
	if got := section(md, "Risks"); got != "- one" {
		t.Fatalf("got %q", got)
	}
}

func TestPRBodyHasNoAbsolutePaths(t *testing.T) {
	p, _ := setup(t, happyModel(), "exit 0")
	if _, err := p.Run(context.Background(), vcs.Issue{ID: "9", Source: "github", Title: "x"}); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"pr.md", "gates.json"} {
		if body := artifact(t, p, "9", name); strings.Contains(body, p.Cfg.WorkRoot) || strings.Contains(body, p.Cfg.RepoDir) {
			t.Fatalf("%s leaks a local path:\n%s", name, body)
		}
	}
}
