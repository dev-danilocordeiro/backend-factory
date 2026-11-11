// Package metrics records pipeline events as append-only JSON lines and
// aggregates them. Every number reported by `factory metrics` comes from
// this file; nothing is estimated.
package metrics

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Kind is the type of event.
type Kind string

const (
	KindReceived Kind = "issue_received"
	KindStage    Kind = "stage_finished"
	KindStopped  Kind = "stopped_needs_human"
	KindPROpened Kind = "pr_opened"
	KindPRMerged Kind = "pr_merged"
	KindRunError Kind = "run_error"
)

// Event is one line of the metrics file.
type Event struct {
	Time       time.Time `json:"time"`
	Kind       Kind      `json:"kind"`
	Issue      string    `json:"issue"`
	Stage      string    `json:"stage,omitempty"`
	Attempt    int       `json:"attempt,omitempty"`
	OK         *bool     `json:"ok,omitempty"`
	Turns      int       `json:"turns,omitempty"`
	CostUSD    float64   `json:"cost_usd,omitempty"`
	DurationMS int64     `json:"duration_ms,omitempty"`
	PRURL      string    `json:"pr_url,omitempty"`
	Reason     string    `json:"reason,omitempty"`
}

// Recorder appends events to a JSONL file.
type Recorder struct {
	path string
	mu   sync.Mutex
	now  func() time.Time
}

// NewRecorder returns a recorder writing to path.
func NewRecorder(path string) *Recorder {
	return &Recorder{path: path, now: func() time.Time { return time.Now().UTC() }}
}

// Record appends e, stamping the time if unset.
func (r *Recorder) Record(e Event) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if e.Time.IsZero() {
		e.Time = r.now()
	}
	if err := os.MkdirAll(filepath.Dir(r.path), 0o755); err != nil {
		return err
	}
	b, err := json.Marshal(e)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(r.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := f.Write(append(b, '\n')); err != nil {
		_ = f.Close()
		return err
	}
	return f.Close()
}

// Read loads all events from path. A missing file yields no events.
func Read(path string) ([]Event, error) {
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }() // read-only
	var out []Event
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	line := 0
	for sc.Scan() {
		line++
		if len(sc.Bytes()) == 0 {
			continue
		}
		var e Event
		if err := json.Unmarshal(sc.Bytes(), &e); err != nil {
			return nil, fmt.Errorf("%s:%d: %w", path, line, err)
		}
		out = append(out, e)
	}
	return out, sc.Err()
}

// Summary is the aggregate view of a metrics file.
type Summary struct {
	IssuesReceived int            `json:"issues_received"`
	PRsOpened      int            `json:"prs_opened"`
	PRsAccepted    int            `json:"prs_accepted"`
	StoppedAt      map[string]int `json:"stopped_at_stage"`
	RunErrors      int            `json:"run_errors"`
	TotalCostUSD   float64        `json:"total_cost_usd"`
	TotalTurns     int            `json:"total_turns"`
	PerIssue       []IssueSummary `json:"per_issue"`
}

// IssueSummary is the outcome of one issue (last run wins).
type IssueSummary struct {
	Issue      string  `json:"issue"`
	Outcome    string  `json:"outcome"`
	Stage      string  `json:"stage,omitempty"`
	Attempts   int     `json:"implement_attempts"`
	CostUSD    float64 `json:"cost_usd"`
	Turns      int     `json:"turns"`
	DurationMS int64   `json:"duration_ms"`
	PRURL      string  `json:"pr_url,omitempty"`
	Merged     bool    `json:"merged"`
}

// Summarize aggregates events. Distinct issues are counted once each.
func Summarize(events []Event) Summary {
	s := Summary{StoppedAt: map[string]int{}}
	per := map[string]*IssueSummary{}
	get := func(id string) *IssueSummary {
		if per[id] == nil {
			per[id] = &IssueSummary{Issue: id, Outcome: "in_progress"}
		}
		return per[id]
	}
	for _, e := range events {
		is := get(e.Issue)
		switch e.Kind {
		case KindReceived:
			// A re-run of the same issue resets its outcome.
			*is = IssueSummary{Issue: e.Issue, Outcome: "in_progress", Merged: is.Merged}
		case KindStage:
			is.CostUSD += e.CostUSD
			is.Turns += e.Turns
			is.DurationMS += e.DurationMS
			if e.Stage == "implement" && e.Attempt > is.Attempts {
				is.Attempts = e.Attempt
			}
		case KindStopped:
			is.Outcome, is.Stage = "needs_human", e.Stage
		case KindPROpened:
			is.Outcome, is.PRURL = "pr_opened", e.PRURL
		case KindPRMerged:
			is.Merged = true
		case KindRunError:
			is.Outcome, is.Stage = "error", e.Stage
		}
	}
	ids := make([]string, 0, len(per))
	for id := range per {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		is := per[id]
		s.IssuesReceived++
		s.TotalCostUSD += is.CostUSD
		s.TotalTurns += is.Turns
		switch is.Outcome {
		case "pr_opened":
			s.PRsOpened++
			if is.Merged {
				s.PRsAccepted++
			}
		case "needs_human":
			s.StoppedAt[is.Stage]++
		case "error":
			s.RunErrors++
		}
		s.PerIssue = append(s.PerIssue, *is)
	}
	return s
}
