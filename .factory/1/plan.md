# Plan

## Files
- `examples/go-service/internal/httpapi/version.go` (new): add the exported package-level `var Version = "dev"` and an unexported `func currentVersion() string` that returns `"dev"` when `Version == ""`. A doc comment gives the `-X` target: `-ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3"`.
- `examples/go-service/internal/httpapi/handler.go`: in `NewHandler`, register `GET /healthz`. It writes `200` through the existing `writeJSON` with a small unexported struct `healthResponse{Status string \`json:"status"\`; Version string \`json:"version"\`}` holding `{"ok", currentVersion()}`. It does not touch `svc`.
- `examples/go-service/internal/httpapi/handler_test.go`: add the tests listed under Tests. Existing tests stay unchanged.

## Steps
1. Create `internal/httpapi/version.go` in `package httpapi`. Add:
   ```go
   // Version is the build version reported by GET /healthz. Set it at build time with
   //   go build -ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3" ./cmd/server
   // An unset or empty value is reported as "dev".
   var Version = "dev"

   func currentVersion() string { if Version == "" { return "dev" }; return Version }
   ```
   It must stay a `var` (not a `const`) holding a plain string literal, or `-X` cannot set it.
2. In `handler.go`, define `type healthResponse struct { Status string \`json:"status"\`; Version string \`json:"version"\` }`.
3. In `NewHandler`, before `return mux`, add:
   ```go
   mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
       writeJSON(w, http.StatusOK, healthResponse{Status: "ok", Version: currentVersion()})
   })
   ```
   The handler reads `Version` on each request, so a value set before or after `NewHandler` both work.
4. Add the tests below to `handler_test.go`. Any test that changes `httpapi.Version` saves the old value and restores it with `t.Cleanup`. No test calls `t.Parallel()`.
5. Run `go test ./...` and `golangci-lint run` in `examples/go-service`.

## Tests
- **AC1:** `handler_test.go` → `TestHealthzDefaultVersion`. It builds `newServer()`, sends `GET /healthz` with an empty body, and checks:
  - the status is `200`;
  - `rec.Header().Get("Content-Type") == "application/json"`;
  - the body decodes into `map[string]any` that `reflect.DeepEqual`s `{"status":"ok","version":"dev"}`.
- **AC1 and spec open question 2:** `TestHealthzEmptyVersionIsDev`. It sets `httpapi.Version = ""` (restored in cleanup) and expects the body to decode exactly to `{"status":"ok","version":"dev"}`.
- **AC2:** `TestHealthzBuildVersion`. It sets `httpapi.Version = "1.2.3"` (restored in cleanup) before `newServer()`, then expects `200` and a body that decodes exactly to `{"status":"ok","version":"1.2.3"}`.
- **AC3:** `TestHealthzDoesNotUseOrderService`. It calls `httpapi.NewHandler(nil)` and sends `GET /healthz`. It expects `200` with no panic, which proves the order service and storage are never called. `TestHealthzDefaultVersion` also covers this, because it uses a fresh server with no orders.
- **AC4:** `TestHealthzPostIs405`. It sends `POST /healthz` to `newServer()` and expects `405`.
- **AC5:** the existing `TestCreateThenGet`, `TestGetUnknownIs404` and `TestMalformedJSONIs400`, plus the existing `internal/order/order_test.go`, all run unchanged and still pass.
- **AC6:** `golangci-lint run` in `examples/go-service` reports no findings. The new non-test code imports nothing new; `handler.go` already imports `net/http` and `encoding/json`, and `version.go` has no imports. revive is satisfied because `Version` has a doc comment.

## Layering
- Every change is in `internal/httpapi`, the transport layer. That is where the HTTP route and the version it reports belong, so no new package is needed.
- The non-test httpapi code does not import `internal/store`, so the `httpapi-not-store` rule is still respected. Only `handler_test.go` imports store, as it already does, and test files are excluded by `!$test`.
- `internal/order` is not touched, so the `order-is-inner` rule is unaffected.
- `cmd/server/main.go` does not change: `Version` is set by the linker, not by code.
- I don't plan any changes to `.golangci.yml`, `go.mod` or build tooling.

## Risks
- **New exported global:** `httpapi.Version` becomes part of the package API. Tests that change it must restore it and must not run in parallel, or they could affect each other.
- **Silent `-X` no-op:** if someone later renames the variable, moves it, or makes it a `const`, `-ldflags -X` stops having any effect and gives no error. The doc comment records the exact path to guard against this.
- **Route conflicts:** none. `GET /healthz` does not overlap the `/orders` patterns. Paths that were 404 before stay 404, except `/healthz` itself.
- **Body format:** the response body ends with a newline, because `writeJSON` uses `json.Encoder`. Tests decode the JSON instead of comparing raw strings, so this is fine.
