package state

import (
	"errors"
	"os"
	"testing"
)

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	s := &State{Issue: "7", Status: StatusRunning, Attempt: 1}
	s.Record(StageSpec, true, "")
	s.Stop(StageGates, "tests failed")
	if err := s.Save(dir); err != nil {
		t.Fatal(err)
	}
	got, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got.Issue != "7" || got.FailedStage != StageGates || got.Status != StatusNeedsHuman || len(got.History) != 1 {
		t.Fatalf("unexpected state: %+v", got)
	}
	if !got.IsTerminal() {
		t.Fatal("needs_human must be terminal")
	}
}

func TestLoadMissing(t *testing.T) {
	_, err := Load(t.TempDir())
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("want ErrNotExist, got %v", err)
	}
}
