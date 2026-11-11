// Package trigger turns a Linear webhook into a pipeline run request.
//
// Verification follows https://linear.app/developers/webhooks: the
// Linear-Signature header is the hex HMAC-SHA256 of the raw body with the
// webhook secret, and webhookTimestamp (ms) must be within a minute.
//
// The shape of label data inside an Issue payload is not documented on that
// page; this code reads data.labels[].{id,name} and updatedFrom.labelIds and
// ignores payloads without them. It has only been tested with recorded
// fixtures, not a live workspace (see README, Limits).
package trigger

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"slices"
	"time"

	"github.com/dev-danilocordeiro/backend-factory/internal/vcs"
)

// MaxSkew is the accepted distance between webhookTimestamp and now.
const MaxSkew = time.Minute

var (
	ErrSignature = errors.New("bad signature")
	ErrStale     = errors.New("stale webhook timestamp")
)

// Verify checks the signature and freshness of a raw Linear payload.
func Verify(secret, signatureHex string, body []byte, now time.Time) error {
	want, err := hex.DecodeString(signatureHex)
	if err != nil {
		return ErrSignature
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	if !hmac.Equal(mac.Sum(nil), want) {
		return ErrSignature
	}
	var ts struct {
		WebhookTimestamp int64 `json:"webhookTimestamp"`
	}
	if err := json.Unmarshal(body, &ts); err != nil {
		return err
	}
	if d := now.Sub(time.UnixMilli(ts.WebhookTimestamp)); d > MaxSkew || d < -MaxSkew {
		return ErrStale
	}
	return nil
}

type label struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type payload struct {
	Action string `json:"action"`
	Type   string `json:"type"`
	URL    string `json:"url"`
	Data   struct {
		ID          string  `json:"id"`
		Identifier  string  `json:"identifier"`
		Title       string  `json:"title"`
		Description string  `json:"description"`
		Labels      []label `json:"labels"`
	} `json:"data"`
	UpdatedFrom struct {
		LabelIDs *[]string `json:"labelIds"`
	} `json:"updatedFrom"`
}

// Match decides whether a verified payload is "label <trigger> was just added
// to an issue". It returns the issue, or ok=false with a reason.
func Match(body []byte, trigger string) (issue vcs.Issue, ok bool, reason string) {
	var p payload
	if err := json.Unmarshal(body, &p); err != nil {
		return issue, false, "unparseable payload"
	}
	if p.Type != "Issue" {
		return issue, false, "not an Issue event"
	}
	i := slices.IndexFunc(p.Data.Labels, func(l label) bool { return l.Name == trigger })
	if i < 0 {
		return issue, false, "trigger label not on issue"
	}
	switch p.Action {
	case "create":
	case "update":
		// Only fire on the transition, not on every later edit of a labelled issue.
		if p.UpdatedFrom.LabelIDs == nil {
			return issue, false, "labels did not change"
		}
		if slices.Contains(*p.UpdatedFrom.LabelIDs, p.Data.Labels[i].ID) {
			return issue, false, "trigger label was already present"
		}
	default:
		return issue, false, "action " + p.Action
	}
	id := p.Data.Identifier
	if id == "" {
		id = p.Data.ID
	}
	issue = vcs.Issue{ID: id, Source: "linear", Title: p.Data.Title, Body: p.Data.Description, URL: p.URL}
	for _, l := range p.Data.Labels {
		issue.Labels = append(issue.Labels, l.Name)
	}
	return issue, true, ""
}

// Dispatcher hands a matched issue to whatever runs the pipeline (in this
// repo: a GitHub repository_dispatch event picked up by the workflow).
type Dispatcher func(vcs.Issue) error

// Handler serves POST /linear.
func Handler(secret, triggerLabel string, dispatch Dispatcher, now func() time.Time) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err != nil {
			http.Error(w, "read error", http.StatusBadRequest)
			return
		}
		if err := Verify(secret, r.Header.Get("Linear-Signature"), body, now()); err != nil {
			http.Error(w, err.Error(), http.StatusUnauthorized)
			return
		}
		issue, ok, reason := Match(body, triggerLabel)
		if !ok {
			// 200 so Linear does not retry an event we intentionally ignore.
			fmt.Fprintf(w, "ignored: %s\n", reason)
			return
		}
		if err := dispatch(issue); err != nil {
			http.Error(w, "dispatch failed", http.StatusBadGateway)
			return
		}
		fmt.Fprintf(w, "dispatched %s\n", issue.ID)
	})
}
