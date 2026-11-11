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

func setVersion(t *testing.T, v string) {
	t.Helper()
	old := httpapi.Version
	httpapi.Version = v
	t.Cleanup(func() { httpapi.Version = old })
}

func checkHealthz(t *testing.T, rec *httptest.ResponseRecorder, version string) {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("status: got %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content type: got %q", ct)
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v (%s)", err, rec.Body)
	}
	want := map[string]any{"status": "ok", "version": version}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("body: got %v, want %v", got, want)
	}
}

func TestHealthzDefaultVersion(t *testing.T) {
	checkHealthz(t, do(newServer(), http.MethodGet, "/healthz", ""), "dev")
}

func TestHealthzEmptyVersionIsDev(t *testing.T) {
	setVersion(t, "")
	checkHealthz(t, do(newServer(), http.MethodGet, "/healthz", ""), "dev")
}

func TestHealthzBuildVersion(t *testing.T) {
	setVersion(t, "1.2.3")
	checkHealthz(t, do(newServer(), http.MethodGet, "/healthz", ""), "1.2.3")
}

func TestHealthzDoesNotUseOrderService(t *testing.T) {
	checkHealthz(t, do(httpapi.NewHandler(nil), http.MethodGet, "/healthz", ""), "dev")
}

func TestHealthzPostIs405(t *testing.T) {
	if rec := do(newServer(), http.MethodPost, "/healthz", ""); rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("got %d", rec.Code)
	}
}
