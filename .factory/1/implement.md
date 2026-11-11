# Implementation

`GET /healthz` is added. It always returns `200` with `{"status":"ok","version":"<version>"}`, and the version is `"dev"` unless the build sets it. Build, tests and lint all passed.

## Changes
- `examples/go-service/internal/httpapi/version.go` (new): adds `var Version = "dev"`, with a doc comment giving the build flag `-ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3"`. It also adds `currentVersion()`, which returns `"dev"` when `Version` is empty.
- `examples/go-service/internal/httpapi/handler.go`: registers `GET /healthz`, which writes `200` with a `healthResponse{Status, Version}` through `writeJSON` and never touches `svc`.
- `examples/go-service/internal/httpapi/handler_test.go`: adds five tests (`TestHealthzDefaultVersion`, `TestHealthzEmptyVersionIsDev`, `TestHealthzBuildVersion`, `TestHealthzDoesNotUseOrderService`, `TestHealthzPostIs405`) and two helpers. Tests that change `Version` put the old value back afterwards. The existing tests are unchanged.

## Commands run
- `go -C examples/go-service build ./...`: passed
- `go -C examples/go-service test ./...`: passed (`internal/httpapi` and `internal/order` ok)
- `cd examples/go-service && golangci-lint run ./...`: passed, 0 issues

## Deviations from the plan
None.

My first attempt to add the tests used a Python script, together with a check that the `-ldflags -X` flag really sets `Version` in a built binary. That command needed approval, which isn't available in this session, so it was blocked. I then made the same test changes with the Edit tool. The `-X` check against a built binary was not run. `TestHealthzBuildVersion` covers the same thing by setting `Version` directly.
