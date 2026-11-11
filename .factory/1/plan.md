# Plan

## Files
- `examples/go-service/internal/httpapi/version.go` (new): holds the package-level `var Version = "dev"`. Its doc comment gives the full `-X` path, `example.com/go-service/internal/httpapi.Version`, and an example command: `go build -ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3" ./cmd/server`.
- `examples/go-service/internal/httpapi/handler.go` (change): in `NewHandler`, register `mux.HandleFunc("GET /healthz", ...)`. The handler calls `writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "version": Version})`. It reads `Version` on each request, not when `NewHandler` runs, and it never touches `svc`. Update the package doc comment to mention the health route.
- `examples/go-service/internal/httpapi/handler_test.go` (change): add the health tests listed under Tests, plus a small `decodeExact` helper. The helper decodes the body into `map[string]any` and compares it with `reflect.DeepEqual` against the expected map, so an extra field fails the test.
- `examples/go-service/cmd/server/main_test.go` (new): an end-to-end test that builds the real binary with `-ldflags -X` and calls `/healthz` on it.

## Steps
1. Create `internal/httpapi/version.go`. It contains:
   ```go
   package httpapi

   // Version is the build version reported by GET /healthz. It defaults to "dev".
   // Set it at build time with:
   //
   //	go build -ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3" ./cmd/server
   var Version = "dev"
   ```
   It must stay a `var` of type `string` with a constant initialiser. A `const`, or a value computed by a function call, cannot be set with `-X`.
2. In `handler.go`, add the `GET /healthz` route inside `NewHandler`, before `return mux`, and reuse the existing `writeJSON`. Do not add any imports.
3. In `handler_test.go`, add:
   - `TestHealthzDefaultVersion`
   - `TestHealthzContentType`
   - `TestHealthzReportsVersionVariable`: saves `httpapi.Version`, sets it to `"1.2.3"`, restores it with `t.Cleanup`, then checks the body. Do not use `t.Parallel`.
   - `TestHealthzEmptyStore`

   All four use the existing `newServer()` and `do()` helpers. Leave the existing tests unchanged.
4. Create `cmd/server/main_test.go` with `package main` and `TestHealthzVersionFromLdflags`:
   - Skip it when `testing.Short()` is true.
   - Run `exec.Command("go", "build", "-ldflags", "-X example.com/go-service/internal/httpapi.Version=1.2.3", "-o", <t.TempDir()/server>, ".")`. Set `cmd.Dir` to the test's working directory, which is `cmd/server`.
   - Pick a free port by calling `net.Listen("tcp", "127.0.0.1:0")`, reading the address, then closing the listener.
   - Start the binary with `ADDR=<that address>` appended to `os.Environ()`. Use `t.Cleanup` to kill the process and wait for it.
   - Poll `GET http://<addr>/healthz` for up to about 5s.
   - Assert status 200, `Content-Type` `application/json`, and a body that decodes to exactly `{"status":"ok","version":"1.2.3"}`.
5. Run `go test ./...` and `golangci-lint run` in `examples/go-service`.

## Tests
1. AC1: `internal/httpapi/handler_test.go` `TestHealthzDefaultVersion` checks for 200 and a body that is exactly `{"status":"ok","version":"dev"}`. This works because no ldflags are set under `go test`.
2. AC2: `internal/httpapi/handler_test.go` `TestHealthzContentType` checks that `rec.Header().Get("Content-Type") == "application/json"`.
3. AC3:
   - `cmd/server/main_test.go` `TestHealthzVersionFromLdflags` is the real `-ldflags -X` build of `./cmd/server`, checking for 200 and `{"status":"ok","version":"1.2.3"}`.
   - `internal/httpapi/handler_test.go` `TestHealthzReportsVersionVariable` is a fast unit-level check that the handler reports the variable's value.
4. AC4:
   - The doc comment on `Version` in `internal/httpapi/version.go` states the full path, `example.com/go-service/internal/httpapi.Version`.
   - `TestHealthzVersionFromLdflags` uses exactly that path, so it fails if the comment and the real path drift apart.
   - `revive` (exported-symbol comment) enforces that the comment exists.
5. AC5: `internal/httpapi/handler_test.go` `TestHealthzEmptyStore` uses `httpapi.NewHandler(order.NewService(store.NewMemory()))` with no orders created and checks for 200.
6. AC6: the existing `TestCreateThenGet`, `TestGetUnknownIs404` and `TestMalformedJSONIs400` must still pass under `go test ./...`. `golangci-lint run` with the unchanged `.golangci.yml` must pass, including depguard `httpapi-not-store`, because the new non-test httpapi code imports only `encoding/json`, `net/http` and `order`.

## Layering
- **The `/healthz` route and `Version`** live in `internal/httpapi`, the transport layer. Health reporting is a transport concern. The new code imports nothing new: no `internal/store`, so it meets `httpapi-not-store`.
- **No change to `internal/order`.** This keeps the domain free of HTTP and build metadata, so `order-is-inner` is untouched.
- **No change to `internal/store`.**
- **`cmd/server/main_test.go`** is a test in the composition root, which already imports every package. depguard has no rules for `cmd`.
- **`.golangci.yml`, `go.mod` and build tooling** do not change.
- **Why `Version` goes in `httpapi`:** putting it there keeps the `NewHandler` signature unchanged. A separate `internal/version` package would also have been allowed.

## Risks
- **Existing callers:** the `NewHandler` signature is unchanged. Existing routes are unchanged, and `/healthz` does not overlap `/orders` patterns. Other methods on `/healthz` get ServeMux's default 405, which the spec allows.
- **Future refactors:** if someone moves or renames `Version`, the `-X` path in build scripts silently stops applying, because the Go linker ignores unknown `-X` targets. The ldflags test and the doc comment guard against this.
- **Changing `Version` to a `const` or computed value** would break `-X`.
- **`TestHealthzVersionFromLdflags`:**
  - It needs the `go` tool on PATH and a free local port, and it is slower.
  - There is a small race on the port between closing the listener and the binary binding to it.
  - It is skipped under `-short`. CI that runs `go test ./...` without `-short` will run it.
- **`TestHealthzReportsVersionVariable`** changes a package-level global. It must not run in parallel with other health tests, and it restores the value with `t.Cleanup`.
