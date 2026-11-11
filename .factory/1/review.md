# Review

## Findings
- [non-blocking] examples/go-service/internal/httpapi/healthz_extra_test.go:437: `TestHealthzVersionSetByLdflags` starts a nested `go test` subprocess. It needs the `go` tool and a writable build cache, and it rebuilds the package, so every non-`-short` test run gets slower. It skips only when `go` is not on PATH. In a restricted CI sandbox (read-only cache, `GOFLAGS=-mod=vendor`, offline proxy settings) it would fail instead of skipping. It also passes its own `-ldflags`, which can conflict with `-ldflags` already set in the inherited `GOFLAGS`. Fix: gate it behind an opt-in env var or build tag, or move the check to a CI step that builds with `-X` and calls `/healthz`.
- [non-blocking] examples/go-service/internal/httpapi/healthz_extra_test.go:495: `TestArchitectureImports` repeats the depguard rules that are already in `.golangci.yml` and enforced by the lint/arch gates. The two copies can drift apart. It reads the platform's build constraints, so files guarded by OS or build tags are not checked. Fix: optional. Keep the test as a cheap guard or delete it and rely on depguard.
- [non-blocking] examples/go-service/internal/httpapi/version.go:532: the exported mutable global `Version` is read on every request without synchronisation. This is safe in production because it is only set by the linker. A future test that sets it while also calling `t.Parallel()` would cause a data race. The tests follow the plan here: they restore the value with `t.Cleanup` and none call `t.Parallel()`. Fix: none required. Optionally note in the doc comment that it must not be changed at runtime.

The acceptance criteria are covered as follows:
- **AC1:** `TestHealthzDefaultVersion` checks the status, the `Content-Type` header and the exact body.
- **AC2:** `TestHealthzBuildVersion` covers it, plus the `-X` subprocess test.
- **AC3:** `TestHealthzDoesNotUseOrderService` covers it. With `NewHandler(nil)`, any use of the order service would panic.
- **AC4:** `TestHealthzPostIs405` covers it.
- **AC5:** the existing tests are unchanged and still pass.
- **AC6:** the lint and arch gates are green.

The empty-value case from the spec's open question is handled and tested. The handler stays in the transport layer, adds no new imports, and nothing unrelated was changed. The `.factory/` files in the diff are pipeline output. There are no security concerns: the endpoint reads no input and returns only a fixed status and the build version.

## Not verified
- I did not build `cmd/server` with `-ldflags -X` and call the real binary. The subprocess test covers this for the test binary, and I am relying on the green test gate for its result.
- How the subprocess test behaves in the real CI environment: cache permissions, `GOFLAGS`, run time.
- How load balancers react to the response, for example whether they expect a particular body or accept the trailing newline.

## Risks
- **Silent no-op of `-X`:** if `Version` is later renamed, moved or made a `const`, builds will report `"dev"` without any error. The subprocess test would catch this, but only in non-`-short` runs. Likelihood is low and impact is low (wrong version in health output).
- **Fragile or slow CI:** the nested `go test` could make CI slow or flaky in sandboxed runners. Likelihood is low to medium, depending on the CI setup. It affects developers, not production.
- **Health check always reports ok:** `/healthz` returns `"ok"` even when dependencies are broken. This is intentional and out of scope per the spec, but operators should not treat it as a readiness check.

VERDICT: APPROVE
