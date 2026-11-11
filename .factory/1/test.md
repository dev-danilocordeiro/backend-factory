# Tests

The six criteria are covered by tests, and all tests pass. The one gap: I couldn't run `golangci-lint` against the service's own config. The import rule it enforces is checked by a new test instead.

## Coverage of acceptance criteria
1. `TestHealthzDefaultVersion` (`internal/httpapi/handler_test.go`, already existed): checks for a 200 and a body of exactly `{"status":"ok","version":"dev"}`.
2. `TestHealthzContentType` (already existed): checks that `Content-Type` is `application/json`. `TestHealthzVersionFromLdflags` also checks this against the real binary.
3. `TestHealthzVersionFromLdflags` (`cmd/server/main_test.go`, already existed): builds the binary with `-ldflags "-X example.com/go-service/internal/httpapi.Version=1.2.3"`, starts it, and checks for a 200 and a body of exactly `{"status":"ok","version":"1.2.3"}`. `TestHealthzReportsVersionVariable` is a smaller in-process check. The ldflags test is skipped under `-short`.
4. `TestVersionVarDocumentsLinkerPath` (new, `internal/httpapi/source_test.go`): parses the package source. It checks that `Version` is a package-level `string` var that starts as `"dev"`, and that its doc comment contains `example.com/go-service/internal/httpapi.Version`.
5. `TestHealthzEmptyStore` (already existed): builds the handler with `httpapi.NewHandler(order.NewService(store.NewMemory()))` and an empty store, and checks for a 200.
6. Partly covered:
   - `TestCreateThenGet`, `TestGetUnknownIs404` and `TestMalformedJSONIs400` still pass.
   - `TestNonTestCodeDoesNotImportStore` (new) fails if any non-test file in `internal/httpapi` imports `internal/store`.
   - I could not run `golangci-lint` with the service's own `.golangci.yml`:
     - Changing into the service directory and reading files outside the worktree were both denied.
     - Run from the worktree root, it reported `0 issues`, but it used the root config, not the service's.
     - Pointing it at the service with `--config examples/go-service/.golangci.yml` failed with a "does not contain main module" error.

## Result
`go -C examples/go-service test -count=1 ./...` passed:
- `cmd/server`: ok
- `internal/httpapi`: ok
- `internal/order`: ok
- `internal/store`: no test files

No test found a fault in the implementation.
