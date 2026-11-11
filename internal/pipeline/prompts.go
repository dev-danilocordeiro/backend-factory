package pipeline

import (
	"fmt"
	"regexp"
	"strings"
)

// promptCtx is what every stage prompt can reference.
type promptCtx struct {
	Issue, Service, Stack, Artifacts, Base string
	Attempt, MaxAttempts                   int
	HasFeedback                            bool
}

func specPrompt(c promptCtx) string {
	return fmt.Sprintf(`Issue %s is in %s/issue.md. The target service is %s (stack: %s).
Write the specification for this issue. Your final message is saved verbatim as %s/spec.md.`,
		c.Issue, c.Artifacts, c.Service, c.Stack, c.Artifacts)
}

func planPrompt(c promptCtx) string {
	return fmt.Sprintf(`Read %[1]s/issue.md and %[1]s/spec.md. The target service is %[2]s (stack: %[3]s).
Write the implementation plan. Your final message is saved verbatim as %[1]s/plan.md.`,
		c.Artifacts, c.Service, c.Stack)
}

func implementPrompt(c promptCtx) string {
	p := fmt.Sprintf(`Implement issue %[1]s in %[2]s (stack: %[3]s). Attempt %[4]d of %[5]d.
Read %[6]s/spec.md and %[6]s/plan.md first.`, c.Issue, c.Service, c.Stack, c.Attempt, c.MaxAttempts, c.Artifacts)
	if c.HasFeedback {
		p += fmt.Sprintf(`
The previous attempt was rejected. The reason is in %s/feedback.md. Fix that first.`, c.Artifacts)
	}
	return p + fmt.Sprintf(`
Change only files under %s. Your final message is saved as %s/implement.md.`, c.Service, c.Artifacts)
}

func testPrompt(c promptCtx) string {
	return fmt.Sprintf(`Write or extend tests for issue %[1]s in %[2]s (stack: %[3]s).
Read %[4]s/spec.md for the acceptance criteria. See the implementation with: git diff %[5]s...HEAD -- %[2]s
Change only test files. Your final message is saved as %[4]s/test.md.`,
		c.Issue, c.Service, c.Stack, c.Artifacts, c.Base)
}

func reviewPrompt(c promptCtx) string {
	return fmt.Sprintf(`Review the change for issue %[1]s in %[2]s (stack: %[3]s).
Inputs: %[4]s/spec.md, %[4]s/plan.md, %[4]s/gates.json (all deterministic gates are green), and git diff %[5]s...HEAD.
Your final message is saved verbatim as %[4]s/review.md.`,
		c.Issue, c.Service, c.Stack, c.Artifacts, c.Base)
}

var (
	statusRe  = regexp.MustCompile(`(?m)^\s*STATUS:\s*(READY|NEEDS_HUMAN)\s*$`)
	verdictRe = regexp.MustCompile(`(?m)^\s*VERDICT:\s*(APPROVE|REQUEST_CHANGES)\s*$`)
)

// lastMatch returns the last captured marker, or "" when absent. A missing
// marker is treated by callers as the conservative outcome.
func lastMatch(re *regexp.Regexp, text string) string {
	m := re.FindAllStringSubmatch(text, -1)
	if len(m) == 0 {
		return ""
	}
	return m[len(m)-1][1]
}

// section returns the body of a "## <name>" markdown section.
func section(md, name string) string {
	lines := strings.Split(md, "\n")
	var out []string
	in := false
	for _, l := range lines {
		if strings.HasPrefix(l, "## ") {
			if in {
				break
			}
			in = strings.EqualFold(strings.TrimSpace(strings.TrimPrefix(l, "## ")), name)
			continue
		}
		if in {
			out = append(out, l)
		}
	}
	return strings.TrimSpace(strings.Join(out, "\n"))
}

var slugRe = regexp.MustCompile(`[^a-z0-9]+`)

func slug(s string) string {
	s = strings.Trim(slugRe.ReplaceAllString(strings.ToLower(s), "-"), "-")
	if len(s) > 40 {
		s = strings.TrimRight(s[:40], "-")
	}
	if s == "" {
		s = "issue"
	}
	return s
}
