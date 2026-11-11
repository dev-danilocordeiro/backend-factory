package trigger

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/dev-danilocordeiro/backend-factory/internal/vcs"
)

var now = time.UnixMilli(1_800_000_000_000)

func sign(secret, body string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(body))
	return hex.EncodeToString(m.Sum(nil))
}

func body(action, updatedFrom string) string {
	return fmt.Sprintf(`{"action":%q,"type":"Issue","url":"https://linear.app/x/issue/ENG-7",
"webhookTimestamp":%d,
"data":{"id":"uuid-7","identifier":"ENG-7","title":"Add health","description":"desc",
"labels":[{"id":"L1","name":"factory"},{"id":"L2","name":"service:go-service"}]},
"updatedFrom":%s}`, action, now.UnixMilli(), updatedFrom)
}

func TestVerify(t *testing.T) {
	b := body("update", `{"labelIds":[]}`)
	if err := Verify("s", sign("s", b), []byte(b), now); err != nil {
		t.Fatal(err)
	}
	if err := Verify("s", sign("other", b), []byte(b), now); !errors.Is(err, ErrSignature) {
		t.Fatalf("wrong secret: %v", err)
	}
	if err := Verify("s", sign("s", b), []byte(b), now.Add(2*time.Minute)); !errors.Is(err, ErrStale) {
		t.Fatalf("replay: %v", err)
	}
}

func TestMatch(t *testing.T) {
	cases := []struct {
		name, body string
		want       bool
	}{
		{"label just added", body("update", `{"labelIds":["L2"]}`), true},
		{"label already there", body("update", `{"labelIds":["L1","L2"]}`), false},
		{"unrelated edit", body("update", `{"title":"old"}`), false},
		{"created with label", body("create", `{}`), true},
		{"removed", body("remove", `{}`), false},
	}
	for _, c := range cases {
		is, ok, reason := Match([]byte(c.body), "factory")
		if ok != c.want {
			t.Errorf("%s: ok=%v reason=%q", c.name, ok, reason)
		}
		if ok && (is.ID != "ENG-7" || is.Source != "linear" || len(is.Labels) != 2) {
			t.Errorf("%s: issue %+v", c.name, is)
		}
	}
}

func TestHandler(t *testing.T) {
	var got []vcs.Issue
	h := Handler("s", "factory", func(i vcs.Issue) error { got = append(got, i); return nil }, func() time.Time { return now })

	b := body("update", `{"labelIds":[]}`)
	req := httptest.NewRequest(http.MethodPost, "/linear", strings.NewReader(b))
	req.Header.Set("Linear-Signature", sign("s", b))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 200 || len(got) != 1 {
		t.Fatalf("code %d got %v body %s", rec.Code, got, rec.Body)
	}

	req = httptest.NewRequest(http.MethodPost, "/linear", strings.NewReader(b))
	req.Header.Set("Linear-Signature", "00")
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 401 || len(got) != 1 {
		t.Fatalf("unsigned must be rejected: %d", rec.Code)
	}
}
