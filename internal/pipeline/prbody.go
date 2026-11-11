package pipeline

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// alwaysUnverified lists what no gate in this pipeline checks, whatever the
// change is. It is printed on every PR so nobody reads "green" as "proven".
var alwaysUnverified = []string{
	"Behaviour outside what the tests exercise (no runtime, load or integration environment is started).",
	"Security beyond the reviewer agent's reading of the diff (no SAST, dependency or secret scanning).",
	"Whether the spec matches what the issue author actually wanted; the spec agent's interpretation is in the artifacts.",
	"Test quality: the gates prove the tests pass, not that they would catch a regression.",
}

func prBody(r *run, cfg Config, lines int, files []string) string {
	var b strings.Builder
	w := func(format string, a ...any) { fmt.Fprintf(&b, format, a...) }

	w("Automated change by **backend-factory** for issue %s. It was not merged and will not be: a human decides.\n\n", issueRef(r))
	if s := section(readOr(r, "spec.md"), "Summary"); s != "" {
		w("## Summary\n\n%s\n\n", s)
	}

	w("## Verified\n\nDeterministic gates (`%s`), run by the orchestrator after the last implement attempt:\n\n", r.gateRep.Stack)
	for _, g := range r.gateRep.Results {
		mark := "x"
		if !g.OK {
			mark = " "
		}
		w("- [%s] **%s**: `%s` (%.1fs)\n", mark, g.Gate, g.Command, float64(g.DurationMS)/1000)
	}
	w("- [x] **scope**: every stage changed only paths it is allowed to (gate config, agents and CI are off limits)\n")
	w("- [x] **size**: %d changed lines outside `.factory/` (cap %d) in %d files\n", lines, cfg.MaxDiffLines, len(files))
	w("- [x] **review agent**: `VERDICT: APPROVE` (full text in `%s/review.md`)\n\n", r.artRel)

	w("## Not verified\n\n")
	for _, s := range alwaysUnverified {
		w("- %s\n", s)
	}
	if s := section(r.reviewMD, "Not verified"); s != "" {
		w("\nFrom the reviewer:\n\n%s\n", s)
	}
	w("\n## Risks\n\n")
	if s := section(r.reviewMD, "Risks"); s != "" {
		w("%s\n", s)
	} else {
		w("The reviewer listed no risks. Treat that as missing information, not as zero risk.\n")
	}

	w("\n## Pipeline\n\n")
	w("- Implement attempts: %d of %d\n", r.st.Attempt, cfg.MaxAttempts)
	w("- Model cost reported by Claude Code across all stages: $%.2f\n", r.st.CostUSD)
	w("- Artifacts: `%s/` (issue, spec, plan, implement, test, gates.json, review, state.json)\n", r.artRel)
	if r.issue.Source == "github" {
		w("\nCloses #%s\n", r.issue.ID)
	}
	return b.String()
}

func issueRef(r *run) string {
	if r.issue.Source == "github" {
		return "#" + r.issue.ID
	}
	if r.issue.URL != "" {
		return fmt.Sprintf("[%s](%s)", r.issue.ID, r.issue.URL)
	}
	return r.issue.ID
}

func readOr(r *run, name string) string {
	b, err := os.ReadFile(filepath.Join(r.artAbs, name))
	if err != nil {
		return ""
	}
	return string(b)
}
