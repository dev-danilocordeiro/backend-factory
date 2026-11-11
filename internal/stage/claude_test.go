package stage

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestArgs(t *testing.T) {
	a := Args(Request{Agent: "spec", Prompt: "do it", MaxTurns: 5, MaxBudgetUSD: 1.5, AllowedTools: []string{"Read", "Grep"}})
	want := []string{"-p", "do it", "--agent", "spec", "--output-format", "json", "--permission-prompts", "none",
		"--max-turns", "5", "--max-budget-usd", "1.50", "--allowedTools", "Read,Grep"}
	if !slices.Equal(a, want) {
		t.Fatalf("got %q", a)
	}
}

func TestParseSingleObject(t *testing.T) {
	r, err := Parse([]byte(`{"type":"result","subtype":"success","is_error":false,"result":"hi","num_turns":3,"total_cost_usd":0.12,"session_id":"s"}`))
	if err != nil {
		t.Fatal(err)
	}
	if r.Text != "hi" || r.NumTurns != 3 || r.TotalCostUSD != 0.12 || r.Failed() {
		t.Fatalf("%+v", r)
	}
}

func TestParseErrorSubtype(t *testing.T) {
	r, err := Parse([]byte("noise\n{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}\n"))
	if err != nil {
		t.Fatal(err)
	}
	if !r.Failed() {
		t.Fatal("error_max_turns must count as failed")
	}
}

func TestParseGarbage(t *testing.T) {
	if _, err := Parse([]byte("not json")); err == nil {
		t.Fatal("want error")
	}
}

func TestRunWithFakeBinary(t *testing.T) {
	dir := t.TempDir()
	bin := filepath.Join(dir, "claude")
	script := "#!/bin/sh\necho \"$FACTORY_STAGE\" > \"$PWD/seen\"\necho '{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\",\"num_turns\":1}'\n"
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	r, err := Claude{Bin: bin}.Run(context.Background(), Request{Agent: "plan", Prompt: "x", Dir: dir, Env: []string{"FACTORY_STAGE=plan"}})
	if err != nil {
		t.Fatal(err)
	}
	if r.Text != "ok" {
		t.Fatalf("%+v", r)
	}
	seen, _ := os.ReadFile(filepath.Join(dir, "seen"))
	if strings.TrimSpace(string(seen)) != "plan" {
		t.Fatalf("env not passed: %q", seen)
	}
}
