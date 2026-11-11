package httpapi_test

import (
	"go/build"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"example.com/go-service/internal/httpapi"
	"example.com/go-service/internal/order"
	"example.com/go-service/internal/store"
)

const ldflagsHelperEnv = "HEALTHZ_LDFLAGS_HELPER_WANT"

// TestHealthzLdflagsHelper is only active when run as a subprocess by
// TestHealthzVersionSetByLdflags; it checks the version the binary was built with.
func TestHealthzLdflagsHelper(t *testing.T) {
	want := os.Getenv(ldflagsHelperEnv)
	if want == "" {
		t.Skip("helper for TestHealthzVersionSetByLdflags")
	}
	checkHealthz(t, do(newServer(), http.MethodGet, "/healthz", ""), want)
}

// Criterion 2 via the real build mechanism: -ldflags "-X <path>.Version=1.2.3".
func TestHealthzVersionSetByLdflags(t *testing.T) {
	if testing.Short() {
		t.Skip("builds a test binary")
	}
	if os.Getenv(ldflagsHelperEnv) != "" {
		t.Skip("running as helper")
	}
	goBin, err := exec.LookPath("go")
	if err != nil {
		t.Skip("go tool not available")
	}
	cmd := exec.Command(goBin, "test", "-count=1",
		"-ldflags=-X=example.com/go-service/internal/httpapi.Version=1.2.3",
		"-run", "^TestHealthzLdflagsHelper$", "-v", ".")
	cmd.Env = append(os.Environ(), ldflagsHelperEnv+"=1.2.3")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("ldflags build/test failed: %v\n%s", err, out)
	}
	if !strings.Contains(string(out), "--- PASS: TestHealthzLdflagsHelper") {
		t.Fatalf("helper did not run and pass:\n%s", out)
	}
}

func TestHealthzIndependentOfOrders(t *testing.T) {
	h := newServer()
	checkHealthz(t, do(h, http.MethodGet, "/healthz", ""), "dev")
	if rec := do(h, http.MethodPost, "/orders", `{"customer":"c1","sku":"A","quantity":2}`); rec.Code != http.StatusCreated {
		t.Fatalf("create: %d %s", rec.Code, rec.Body)
	}
	checkHealthz(t, do(h, http.MethodGet, "/healthz", ""), "dev")
	// The health check must not have consumed an order id.
	if rec := do(h, http.MethodGet, "/orders/ord-1", ""); rec.Code != http.StatusOK {
		t.Fatalf("get ord-1: %d %s", rec.Code, rec.Body)
	}
}

func TestHealthzPostWithNilServiceIs405(t *testing.T) {
	rec := do(httpapi.NewHandler(nil), http.MethodPost, "/healthz", "")
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("got %d", rec.Code)
	}
	if allow := rec.Header().Get("Allow"); !strings.Contains(allow, http.MethodGet) {
		t.Fatalf("Allow header: %q", allow)
	}
}

func TestHealthzDoesNotShadowOtherPaths(t *testing.T) {
	h := httpapi.NewHandler(order.NewService(store.NewMemory()))
	if rec := do(h, http.MethodGet, "/healthz/extra", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("/healthz/extra: got %d", rec.Code)
	}
	if rec := do(h, http.MethodGet, "/orders/healthz", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("/orders/healthz: got %d", rec.Code)
	}
}

// Criterion 6: the depguard architecture rules, checked without golangci-lint.
func TestArchitectureImports(t *testing.T) {
	const mod = "example.com/go-service/internal/"
	cases := []struct {
		dir  string
		deny []string
	}{
		{".", []string{mod + "store"}},
		{filepath.Join("..", "order"), []string{mod + "store", mod + "httpapi", "net/http"}},
	}
	for _, c := range cases {
		pkg, err := build.ImportDir(c.dir, 0)
		if err != nil {
			t.Fatalf("import %s: %v", c.dir, err)
		}
		for _, imp := range pkg.Imports { // non-test files only
			for _, d := range c.deny {
				if imp == d {
					t.Errorf("%s imports forbidden package %s", c.dir, d)
				}
			}
		}
	}
}
