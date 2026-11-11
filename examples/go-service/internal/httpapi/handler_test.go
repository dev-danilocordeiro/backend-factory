package httpapi_test

import (
	"net/http"
	"net/http/httptest"
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
