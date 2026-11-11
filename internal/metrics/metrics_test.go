package metrics

import (
	"path/filepath"
	"testing"
)

func ok(b bool) *bool { return &b }

func TestRecordAndSummarize(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.jsonl")
	r := NewRecorder(path)
	evs := []Event{
		{Kind: KindReceived, Issue: "1"},
		{Kind: KindStage, Issue: "1", Stage: "spec", OK: ok(true), Turns: 3, CostUSD: 0.5},
		{Kind: KindStage, Issue: "1", Stage: "implement", Attempt: 2, OK: ok(true), Turns: 10, CostUSD: 1},
		{Kind: KindPROpened, Issue: "1", PRURL: "u1"},
		{Kind: KindPRMerged, Issue: "1"},
		{Kind: KindReceived, Issue: "2"},
		{Kind: KindStage, Issue: "2", Stage: "spec", OK: ok(false), Turns: 2, CostUSD: 0.25},
		{Kind: KindStopped, Issue: "2", Stage: "spec"},
		{Kind: KindReceived, Issue: "3"},
		{Kind: KindPROpened, Issue: "3", PRURL: "u3"},
	}
	for _, e := range evs {
		if err := r.Record(e); err != nil {
			t.Fatal(err)
		}
	}
	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	s := Summarize(got)
	if s.IssuesReceived != 3 || s.PRsOpened != 2 || s.PRsAccepted != 1 || s.StoppedAt["spec"] != 1 {
		t.Fatalf("bad summary: %+v", s)
	}
	if s.TotalCostUSD != 1.75 || s.TotalTurns != 15 {
		t.Fatalf("bad totals: %+v", s)
	}
	if s.PerIssue[0].Attempts != 2 {
		t.Fatalf("attempts: %+v", s.PerIssue[0])
	}
}

func TestRerunResetsOutcome(t *testing.T) {
	s := Summarize([]Event{
		{Kind: KindReceived, Issue: "1"},
		{Kind: KindStopped, Issue: "1", Stage: "gates"},
		{Kind: KindReceived, Issue: "1"},
		{Kind: KindPROpened, Issue: "1", PRURL: "u"},
	})
	if s.IssuesReceived != 1 || s.PRsOpened != 1 || len(s.StoppedAt) != 0 {
		t.Fatalf("bad summary: %+v", s)
	}
}

func TestReadMissingFile(t *testing.T) {
	evs, err := Read(filepath.Join(t.TempDir(), "nope.jsonl"))
	if err != nil || evs != nil {
		t.Fatalf("got %v %v", evs, err)
	}
}
