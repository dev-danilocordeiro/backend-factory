package httpapi_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"example.com/go-service/internal/httpapi"
	"example.com/go-service/internal/order"
	"example.com/go-service/internal/store"
)

func newServer() http.Handler {
	return httpapi.NewHandler(order.NewService(store.NewMemory()))
}

func do(h http.Handler, method, path, body string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(method, path, strings.NewReader(body)))
	return rec
}

func TestCreateThenGet(t *testing.T) {
	h := newServer()
	rec := do(h, http.MethodPost, "/orders", `{"customer":"c1","sku":"A","quantity":2}`)
	if rec.Code != http.StatusCreated || !strings.Contains(rec.Body.String(), `"id":"ord-1"`) {
		t.Fatalf("create: %d %s", rec.Code, rec.Body)
	}
	rec = do(h, http.MethodGet, "/orders/ord-1", "")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"sku":"A"`) {
		t.Fatalf("get: %d %s", rec.Code, rec.Body)
	}
}

func TestGetUnknownIs404(t *testing.T) {
	if rec := do(newServer(), http.MethodGet, "/orders/nope", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestMalformedJSONIs400(t *testing.T) {
	if rec := do(newServer(), http.MethodPost, "/orders", `{`); rec.Code != http.StatusBadRequest {
		t.Fatalf("got %d", rec.Code)
	}
}

// decodeExact fails the test unless the body decodes to exactly want.
func decodeExact(t *testing.T, rec *httptest.ResponseRecorder, want map[string]any) {
	t.Helper()
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode %q: %v", rec.Body, err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("body = %v, want %v", got, want)
	}
}

func TestHealthzDefaultVersion(t *testing.T) {
	rec := do(newServer(), http.MethodGet, "/healthz", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("got %d", rec.Code)
	}
	decodeExact(t, rec, map[string]any{"status": "ok", "version": "dev"})
}

func TestHealthzContentType(t *testing.T) {
	rec := do(newServer(), http.MethodGet, "/healthz", "")
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("Content-Type = %q", ct)
	}
}

func TestHealthzReportsVersionVariable(t *testing.T) {
	old := httpapi.Version
	t.Cleanup(func() { httpapi.Version = old })
	h := newServer()
	httpapi.Version = "1.2.3"
	rec := do(h, http.MethodGet, "/healthz", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("got %d", rec.Code)
	}
	decodeExact(t, rec, map[string]any{"status": "ok", "version": "1.2.3"})
}

func TestHealthzEmptyStore(t *testing.T) {
	h := httpapi.NewHandler(order.NewService(store.NewMemory()))
	if rec := do(h, http.MethodGet, "/healthz", ""); rec.Code != http.StatusOK {
		t.Fatalf("got %d", rec.Code)
	}
}
