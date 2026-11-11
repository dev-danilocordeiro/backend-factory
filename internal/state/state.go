// Package state persists the progress of one issue through the pipeline.
package state

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// Stage names one step of the pipeline.
type Stage string

const (
	StageSpec      Stage = "spec"
	StagePlan      Stage = "plan"
	StageImplement Stage = "implement"
	StageTest      Stage = "test"
	StageGates     Stage = "gates"
	StageReview    Stage = "review"
	StagePR        Stage = "pr"
)

// Status is the overall outcome of a run.
type Status string

const (
	StatusRunning    Status = "running"
	StatusNeedsHuman Status = "needs_human"
	StatusPROpened   Status = "pr_opened"
	StatusError      Status = "error"
)

// Entry records one completed step.
type Entry struct {
	Time    time.Time `json:"time"`
	Stage   Stage     `json:"stage"`
	Attempt int       `json:"attempt"`
	OK      bool      `json:"ok"`
	Note    string    `json:"note,omitempty"`
}

// State is the persisted state of one issue run.
type State struct {
	Issue       string  `json:"issue"`
	Source      string  `json:"source"`
	Title       string  `json:"title"`
	Service     string  `json:"service"`
	Base        string  `json:"base"`
	Branch      string  `json:"branch"`
	Stage       Stage   `json:"stage"`
	Attempt     int     `json:"attempt"`
	Status      Status  `json:"status"`
	FailedStage Stage   `json:"failed_stage,omitempty"`
	Reason      string  `json:"reason,omitempty"`
	PRURL       string  `json:"pr_url,omitempty"`
	CostUSD     float64 `json:"cost_usd"`
	History     []Entry `json:"history"`
}

const fileName = "state.json"

// Load reads state from dir. It returns os.ErrNotExist if there is none.
func Load(dir string) (*State, error) {
	b, err := os.ReadFile(filepath.Join(dir, fileName))
	if err != nil {
		return nil, err
	}
	var s State
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, fmt.Errorf("parse state: %w", err)
	}
	return &s, nil
}

// Save writes state atomically to dir.
func (s *State) Save(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := filepath.Join(dir, fileName+".tmp")
	if err := os.WriteFile(tmp, append(b, '\n'), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(dir, fileName))
}

// Record appends a history entry and moves the current stage.
func (s *State) Record(stage Stage, ok bool, note string) {
	s.Stage = stage
	s.History = append(s.History, Entry{Time: time.Now().UTC(), Stage: stage, Attempt: s.Attempt, OK: ok, Note: note})
}

// Stop marks the run as needing a human, remembering where it failed.
func (s *State) Stop(stage Stage, reason string) {
	s.Status = StatusNeedsHuman
	s.FailedStage = stage
	s.Reason = reason
}

// IsTerminal reports whether the run has finished one way or another.
func (s *State) IsTerminal() bool {
	return s.Status == StatusNeedsHuman || s.Status == StatusPROpened || s.Status == StatusError
}

// ErrTerminal is returned when trying to resume a finished run.
var ErrTerminal = errors.New("run already finished")
