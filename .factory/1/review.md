# Review

## Findings
- [non-blocking] examples/go-service/cmd/server/main_test.go:386-416: the test gets a free port by opening a listener and closing it again, then starts the binary on that port. If another process takes the port in between, the binary exits straight away, and the loop keeps polling for the full 5s. It then fails with a vague "connection refused", or passes against the wrong server if that process happens to answer `/healthz`. Fix: during the poll, check whether the process has exited, for example by running `srv.Wait()` in a goroutine with a done channel. Also capture the binary's stderr so the failure message says why it stopped.
- [non-blocking] examples/go-service/cmd/server/main_test.go:379: the test runs whatever `go` is first on PATH. That may not be the toolchain running the tests, which can surprise people on machines with several Go versions. Fix: use `filepath.Join(runtime.GOROOT(), "bin", "go")`, or accept this as a known limitation.
- [non-blocking] examples/go-service/internal/httpapi/source_test.go:621-632: `TestNonTestCodeDoesNotImportStore` repeats the depguard rule `httpapi-not-store` that the lint gate already enforces. That is harmless, but the rule now lives in two places. Fix: keep it only if lint may not run in some environments; otherwise remove it.

All criteria are met. AC1, AC2 and AC5 are covered by the new tests in `handler_test.go`. AC3 is covered by a real `-ldflags -X` build of the binary. The module path `example.com/go-service` in `go.mod` matches the documented `-X` path, and `main.go` reads `ADDR` as the test expects. AC4 is covered by the doc comment plus a source-parsing test. AC6 is covered by the green gates, the unchanged existing tests, and the fact that the change adds no new imports. `writeJSON` sets `Content-Type: application/json`. The handler reads `Version` on each request and does not touch `svc`. The diff changes no unrelated product code; the only extra files are the factory's `.factory/1/*` records.

## Not verified
- I did not run the tests or lint myself. I relied on `gates.json`, which shows all gates green.
- `.factory/1/test.md` says the tester could not run `golangci-lint` with the service's own `.golangci.yml`. I assume the green lint gate (`gates/go.sh lint examples/go-service`) did use that config, but I did not check this.
- I could not confirm that the lint and test gates run without `-short`, which is needed for the ldflags end-to-end test to run in CI.
- I did not test how real load balancers behave against the endpoint, such as their timeouts or whether they send HEAD or GET.

## Risks
- **Flaky end-to-end test:** the port race and the dependence on the `go` tool could occasionally fail CI. This is unlikely and affects only developers and CI, not production.
- **Silent version loss:** if `Version` is later moved or renamed, build scripts using the old `-X` path will quietly produce `"dev"`, because the Go linker ignores unknown `-X` targets. The source test and the ldflags test reduce this risk. It is low likelihood and affects operators who read the version.
- **Public version exposure:** `/healthz` has no authentication and shows the build version to anyone who can reach it. The spec puts authentication out of scope and this is common practice, so the risk is low.

VERDICT: APPROVE
