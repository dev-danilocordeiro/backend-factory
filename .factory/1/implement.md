# Implementation

`GET /healthz` is in place and returns `200` with `{"status":"ok","version":"<version>"}`. The version defaults to `"dev"` and can be set at build time. Build, all tests (old and new) and `golangci-lint` pass.

## Changes
- `examples/go-service/internal/httpapi/version.go` (new): adds the `string` variable `var Version = "dev"`. Its doc comment gives the full `-X` path, `example.com/go-service/internal/httpapi.Version`, and an example `go build -ldflags` command.
- `examples/go-service/internal/httpapi/handler.go`: registers `GET /healthz` in `NewHandler`, using the existing `writeJSON`. It reads `Version` on each request and never touches `svc`. The package doc comment now mentions the health route. No imports were added.
- `examples/go-service/internal/httpapi/handler_test.go`: adds a `decodeExact` helper that fails on any extra field, plus four tests: `TestHealthzDefaultVersion`, `TestHealthzContentType`, `TestHealthzReportsVersionVariable` and `TestHealthzEmptyStore`. The existing tests are unchanged.
- `examples/go-service/cmd/server/main_test.go` (new): `TestHealthzVersionFromLdflags` builds the real binary with `-ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3"`, starts it on a free port and checks the status, `Content-Type` and exact body. It is skipped under `-short`.

## Commands run
- `go -C examples/go-service build ./...`: passed
- `go -C examples/go-service test ./... -count=1 -v`: passed (all tests, including the ldflags end-to-end test)
- `cd examples/go-service && golangci-lint run ./...`: passed (0 issues)

## Deviations from the plan
- In `TestHealthzReportsVersionVariable`, the handler is built before `Version` is set to `"1.2.3"`. This also checks that the value is read on each request, not when `NewHandler` runs.
- In `TestHealthzVersionFromLdflags`, `cmd.Dir` is not set explicitly. The test already runs in `cmd/server`, so it gets the same result.
