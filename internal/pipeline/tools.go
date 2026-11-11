package pipeline

import (
	"github.com/dev-danilocordeiro/backend-factory/internal/gates"
	"github.com/dev-danilocordeiro/backend-factory/internal/state"
)

// Permission rules pre-approved per stage. Anything not listed is denied:
// headless runs use --permission-prompts none, so nothing can be approved
// interactively. The agent definitions in .claude/agents/ restrict the tool
// set further; these rules decide which concrete commands run.
var (
	readTools = []string{"Read", "Grep", "Glob"}
	gitRead   = []string{"Bash(git diff *)", "Bash(git log *)", "Bash(git status *)", "Bash(git show *)"}
	editTools = []string{"Edit", "Write"}
)

func buildTools(stack gates.Stack) []string {
	switch stack {
	case gates.StackGo:
		return []string{"Bash(cd *)", "Bash(go build *)", "Bash(go test *)", "Bash(go vet *)", "Bash(go -C *)",
			"Bash(gofmt *)", "Bash(golangci-lint run *)"}
	case gates.StackMaven:
		return []string{"Bash(cd *)", "Bash(mvn *)"}
	case gates.StackGradle:
		return []string{"Bash(cd *)", "Bash(./gradlew *)", "Bash(gradle *)"}
	}
	return nil
}

func allowedTools(st state.Stage, stack gates.Stack) []string {
	var out []string
	add := func(xs ...[]string) {
		for _, x := range xs {
			out = append(out, x...)
		}
	}
	switch st {
	case state.StageSpec, state.StagePlan:
		add(readTools)
	case state.StageImplement:
		add(readTools, editTools, buildTools(stack))
	case state.StageTest:
		add(readTools, editTools, buildTools(stack), gitRead)
	case state.StageReview:
		add(readTools, gitRead)
	}
	return out
}
