// Command factory takes an issue to a pull request with headless Claude Code.
//
//	factory run     --issue 12            run one GitHub issue
//	factory run     --issue-file x.json   run an issue given as JSON (e.g. from Linear)
//	factory metrics [--refresh] [--json]  aggregate metrics, optionally checking merged PRs
//	factory serve   --addr :8080          receive Linear webhooks and dispatch runs
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	"github.com/dev-danilocordeiro/backend-factory/internal/gates"
	"github.com/dev-danilocordeiro/backend-factory/internal/metrics"
	"github.com/dev-danilocordeiro/backend-factory/internal/pipeline"
	"github.com/dev-danilocordeiro/backend-factory/internal/stage"
	"github.com/dev-danilocordeiro/backend-factory/internal/state"
	"github.com/dev-danilocordeiro/backend-factory/internal/trigger"
	"github.com/dev-danilocordeiro/backend-factory/internal/vcs"
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	if len(os.Args) < 2 {
		usage()
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var err error
	switch os.Args[1] {
	case "run":
		err = cmdRun(ctx, os.Args[2:])
	case "metrics":
		err = cmdMetrics(ctx, os.Args[2:])
	case "serve":
		err = cmdServe(ctx, os.Args[2:])
	default:
		usage()
	}
	if err != nil {
		log.Fatal(err)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: factory run|metrics|serve [flags]")
	os.Exit(2)
}

func repoRoot() string {
	out, err := exec.Command("git", "rev-parse", "--show-toplevel").Output()
	if err != nil {
		log.Fatal("not inside a git repository")
	}
	return strings.TrimSpace(string(out))
}

func ghRepo(dir string) string {
	if r := os.Getenv("GITHUB_REPOSITORY"); r != "" {
		return r
	}
	cmd := exec.Command("gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner")
	cmd.Dir = dir
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func defaultMetrics(root string) string { return filepath.Join(root, ".factory", "metrics.jsonl") }

func cmdRun(ctx context.Context, args []string) error {
	root := repoRoot()
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	issueNum := fs.String("issue", "", "GitHub issue number")
	issueFile := fs.String("issue-file", "", "JSON file with {id,source,title,body,labels,url}")
	service := fs.String("service", "", "service dir relative to repo root (default: from a service:<name> label, under --services-root)")
	servicesRoot := fs.String("services-root", "examples", "where service:<name> labels resolve")
	base := fs.String("base", "main", "base branch for the PR")
	repo := fs.String("repo", "", "owner/name (default: from gh)")
	remote := fs.Bool("remote", true, "push, open PR and comment on the issue")
	attempts := fs.Int("max-attempts", 3, "implement attempts before asking a human")
	maxLines := fs.Int("max-diff-lines", 400, "max changed lines outside .factory/ in one PR")
	model := fs.String("model", "", "model for every stage (default: the CLI's)")
	metricsPath := fs.String("metrics", defaultMetrics(root), "metrics JSONL file")
	workRoot := fs.String("work-root", "", "parent dir for worktrees (default: user cache dir)")
	claudeBin := fs.String("claude", "claude", "Claude Code binary")
	budgetScale := fs.Float64("budget-scale", 1, "multiply every per-stage USD budget by this")
	_ = fs.Parse(args) // ExitOnError: Parse exits on bad flags

	if *repo == "" {
		*repo = ghRepo(root)
	}
	gh := vcs.GH{Repo: *repo, Dir: root}

	var issue vcs.Issue
	switch {
	case *issueFile != "":
		b, err := os.ReadFile(*issueFile)
		if err != nil {
			return err
		}
		if err := json.Unmarshal(b, &issue); err != nil {
			return fmt.Errorf("issue file: %w", err)
		}
	case *issueNum != "":
		var err error
		if issue, err = gh.Issue(ctx, *issueNum); err != nil {
			return err
		}
	default:
		return errors.New("need --issue or --issue-file")
	}

	if *service == "" {
		for _, l := range issue.Labels {
			if name, ok := strings.CutPrefix(l, "service:"); ok {
				*service = filepath.ToSlash(filepath.Join(*servicesRoot, name))
			}
		}
	}
	if *service == "" {
		return errors.New("no --service and no service:<name> label on the issue")
	}
	if *workRoot == "" {
		cache, err := os.UserCacheDir()
		if err != nil {
			return err
		}
		*workRoot = filepath.Join(cache, "backend-factory", "work")
	}

	limits := pipeline.DefaultLimits()
	for k, l := range limits {
		l.MaxBudgetUSD *= *budgetScale
		limits[k] = l
	}
	p := &pipeline.Pipeline{
		Cfg: pipeline.Config{RepoDir: root, WorkRoot: *workRoot, Base: *base, Service: *service,
			MaxAttempts: *attempts, MaxDiffLines: *maxLines, Model: *model, Limits: limits,
			NeedsHumanLabel: "factory:needs-human", Remote: *remote},
		Runner:  stage.Claude{Bin: *claudeBin},
		Gates:   gates.Runner{ScriptsDir: filepath.Join(root, "gates"), Timeout: 15 * time.Minute},
		Metrics: metrics.NewRecorder(*metricsPath),
		Log:     log.Printf,
	}
	if *remote {
		p.GitHub = gh
	}
	st, err := p.Run(ctx, issue)
	if st != nil {
		fmt.Printf("issue %s: %s", st.Issue, st.Status)
		if st.FailedStage != "" {
			fmt.Printf(" at %s (%s)", st.FailedStage, st.Reason)
		}
		if st.PRURL != "" {
			fmt.Printf(" %s", st.PRURL)
		}
		fmt.Printf(" cost=$%.2f\n", st.CostUSD)
	}
	if err == nil && st != nil && st.Status == state.StatusNeedsHuman {
		os.Exit(3) // distinct exit code so CI can tell "stopped for a human" from "crashed"
	}
	return err
}

func cmdMetrics(ctx context.Context, args []string) error {
	root := repoRoot()
	fs := flag.NewFlagSet("metrics", flag.ExitOnError)
	path := fs.String("metrics", defaultMetrics(root), "metrics JSONL file")
	refresh := fs.Bool("refresh", false, "ask GitHub which opened PRs were merged and record it")
	asJSON := fs.Bool("json", false, "print JSON")
	_ = fs.Parse(args) // ExitOnError: Parse exits on bad flags

	evs, err := metrics.Read(*path)
	if err != nil {
		return err
	}
	if *refresh {
		gh := vcs.GH{Repo: ghRepo(root), Dir: root}
		rec := metrics.NewRecorder(*path)
		for _, is := range metrics.Summarize(evs).PerIssue {
			if is.PRURL == "" || is.Merged {
				continue
			}
			merged, err := gh.PRMerged(ctx, is.PRURL)
			if err != nil {
				return err
			}
			if merged {
				if err := rec.Record(metrics.Event{Kind: metrics.KindPRMerged, Issue: is.Issue, PRURL: is.PRURL}); err != nil {
					return err
				}
			}
		}
		if evs, err = metrics.Read(*path); err != nil {
			return err
		}
	}
	s := metrics.Summarize(evs)
	if *asJSON {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(s)
	}
	fmt.Printf("issues received: %d\nPRs opened: %d\nPRs accepted (merged by a human): %d\nrun errors: %d\n",
		s.IssuesReceived, s.PRsOpened, s.PRsAccepted, s.RunErrors)
	fmt.Printf("stopped for a human, by stage: %v\ntotal turns: %d\ntotal cost reported by Claude Code: $%.2f\n\n",
		s.StoppedAt, s.TotalTurns, s.TotalCostUSD)
	tw := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "ISSUE\tOUTCOME\tSTAGE\tATTEMPTS\tTURNS\tCOST\tMINUTES\tMERGED\tPR")
	for _, is := range s.PerIssue {
		fmt.Fprintf(tw, "%s\t%s\t%s\t%d\t%d\t$%.2f\t%.1f\t%v\t%s\n", is.Issue, is.Outcome, is.Stage, is.Attempts,
			is.Turns, is.CostUSD, float64(is.DurationMS)/60000, is.Merged, is.PRURL)
	}
	return tw.Flush()
}

func cmdServe(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	addr := fs.String("addr", ":8080", "listen address")
	label := fs.String("label", "factory", "Linear label that triggers a run")
	repo := fs.String("repo", os.Getenv("GITHUB_REPOSITORY"), "owner/name receiving repository_dispatch")
	_ = fs.Parse(args) // ExitOnError: Parse exits on bad flags
	secret := os.Getenv("LINEAR_WEBHOOK_SECRET")
	if secret == "" || *repo == "" {
		return errors.New("LINEAR_WEBHOOK_SECRET and --repo are required")
	}
	dispatch := func(is vcs.Issue) error {
		payload, _ := json.Marshal(map[string]any{"event_type": "factory-linear", "client_payload": map[string]any{"issue": is}})
		cmd := exec.CommandContext(ctx, "gh", "api", "repos/"+*repo+"/dispatches", "--input", "-")
		cmd.Stdin = strings.NewReader(string(payload))
		out, err := cmd.CombinedOutput()
		if err != nil {
			log.Printf("dispatch %s: %v: %s", is.ID, err, out)
		}
		return err
	}
	mux := http.NewServeMux()
	mux.Handle("/linear", trigger.Handler(secret, *label, dispatch, time.Now))
	srv := &http.Server{Addr: *addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() { <-ctx.Done(); _ = srv.Close() }()
	log.Printf("listening on %s", *addr)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
