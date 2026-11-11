// Package gates runs the deterministic checks that decide whether a change
// may leave the pipeline: build, lint, architecture rules and tests.
package gates

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// Stack is the detected build system of a service.
type Stack string

const (
	StackGo     Stack = "go"
	StackMaven  Stack = "java-maven"
	StackGradle Stack = "java-gradle"
)

// Order is the gate sequence. Cheap and decisive checks run first; the run
// stops at the first red gate so feedback is about one problem at a time.
var Order = []string{"build", "lint", "arch", "test"}

// Detect inspects dir for a known build file.
func Detect(dir string) (Stack, error) {
	exists := func(name string) bool {
		_, err := os.Stat(filepath.Join(dir, name))
		return err == nil
	}
	switch {
	case exists("go.mod"):
		return StackGo, nil
	case exists("pom.xml"):
		return StackMaven, nil
	case exists("build.gradle"), exists("build.gradle.kts"):
		return StackGradle, nil
	}
	return "", fmt.Errorf("no go.mod, pom.xml or build.gradle[.kts] in %s", dir)
}

// Result is the outcome of one gate.
type Result struct {
	Gate       string `json:"gate"`
	Command    string `json:"command"`
	OK         bool   `json:"ok"`
	ExitCode   int    `json:"exit_code"`
	DurationMS int64  `json:"duration_ms"`
	Output     string `json:"output,omitempty"` // tail, only kept for red gates
}

// Report is the outcome of a full gate run.
type Report struct {
	Stack   Stack    `json:"stack"`
	Results []Result `json:"results"`
	Skipped []string `json:"skipped,omitempty"`
}

// Green reports whether every gate ran and passed.
func (r Report) Green() bool {
	if len(r.Skipped) > 0 || len(r.Results) == 0 {
		return false
	}
	for _, g := range r.Results {
		if !g.OK {
			return false
		}
	}
	return true
}

// FirstRed returns the first failing gate, if any.
func (r Report) FirstRed() *Result {
	for i := range r.Results {
		if !r.Results[i].OK {
			return &r.Results[i]
		}
	}
	return nil
}

// Runner executes the gate scripts found in ScriptsDir.
type Runner struct {
	ScriptsDir string        // directory with go.sh, java-maven.sh, java-gradle.sh
	Timeout    time.Duration // per gate
	OutputTail int           // bytes of output kept for red gates
}

// Run detects the stack of serviceDir and runs every gate in Order.
func (g Runner) Run(ctx context.Context, serviceDir string) (Report, error) {
	stack, err := Detect(serviceDir)
	if err != nil {
		return Report{}, err
	}
	rep := Report{Stack: stack}
	script := filepath.Join(g.ScriptsDir, string(stack)+".sh")
	for i, gate := range Order {
		res, err := g.runOne(ctx, script, gate, serviceDir)
		if err != nil {
			return rep, err
		}
		rep.Results = append(rep.Results, res)
		if !res.OK {
			rep.Skipped = append(rep.Skipped, Order[i+1:]...)
			break
		}
	}
	return rep, nil
}

func (g Runner) runOne(ctx context.Context, script, gate, dir string) (Result, error) {
	if g.Timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, g.Timeout)
		defer cancel()
	}
	start := time.Now()
	cmd := exec.CommandContext(ctx, script, gate, dir)
	var out bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &out
	err := cmd.Run()
	res := Result{Gate: gate, Command: fmt.Sprintf("%s %s %s", filepath.Base(script), gate, dir), DurationMS: time.Since(start).Milliseconds()}
	var exitErr *exec.ExitError
	switch {
	case err == nil:
		res.OK = true
	case errors.As(err, &exitErr):
		res.ExitCode = exitErr.ExitCode()
	case ctx.Err() != nil:
		res.ExitCode = -1
		out.WriteString("\ngate timed out")
	default:
		return res, fmt.Errorf("gate %s: %w", gate, err)
	}
	if !res.OK {
		n := g.OutputTail
		if n <= 0 {
			n = 6000
		}
		s := out.String()
		if len(s) > n {
			s = "…" + s[len(s)-n:]
		}
		res.Output = s
	}
	return res, nil
}
