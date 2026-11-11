Automated change by **backend-factory** for issue #1. It was not merged and will not be: a human decides.

## Summary

The service gets a new endpoint, `GET /healthz`, so load balancers can check whether it is up. It always responds `200 OK` with the JSON body `{"status":"ok","version":"<version>"}`. The version comes from a package-level string variable that the build can set with `go build -ldflags "-X <import path>.<Name>=<value>"`. If the build does not set it, the version is `"dev"`.

## Verified

Deterministic gates (`go`), run by the orchestrator after the last implement attempt:

- [x] **build**: `go.sh build /home/rootcanis/.cache/backend-factory/work/1/examples/go-service` (0.4s)
- [x] **lint**: `go.sh lint /home/rootcanis/.cache/backend-factory/work/1/examples/go-service` (0.2s)
- [x] **arch**: `go.sh arch /home/rootcanis/.cache/backend-factory/work/1/examples/go-service` (0.2s)
- [x] **test**: `go.sh test /home/rootcanis/.cache/backend-factory/work/1/examples/go-service` (0.5s)
- [x] **scope**: every stage changed only paths it is allowed to (gate config, agents and CI are off limits)
- [x] **size**: 185 changed lines outside `.factory/` (cap 400) in 4 files
- [x] **review agent**: `VERDICT: APPROVE` (full text in `.factory/1/review.md`)

## Not verified

- Behaviour outside what the tests exercise (no runtime, load or integration environment is started).
- Security beyond the reviewer agent's reading of the diff (no SAST, dependency or secret scanning).
- Whether the spec matches what the issue author actually wanted; the spec agent's interpretation is in the artifacts.
- Test quality: the gates prove the tests pass, not that they would catch a regression.

From the reviewer:

- I did not build `cmd/server` with `-ldflags -X` and call the real binary. The subprocess test covers this for the test binary, and I am relying on the green test gate for its result.
- How the subprocess test behaves in the real CI environment: cache permissions, `GOFLAGS`, run time.
- How load balancers react to the response, for example whether they expect a particular body or accept the trailing newline.

## Risks

- **Silent no-op of `-X`:** if `Version` is later renamed, moved or made a `const`, builds will report `"dev"` without any error. The subprocess test would catch this, but only in non-`-short` runs. Likelihood is low and impact is low (wrong version in health output).
- **Fragile or slow CI:** the nested `go test` could make CI slow or flaky in sandboxed runners. Likelihood is low to medium, depending on the CI setup. It affects developers, not production.
- **Health check always reports ok:** `/healthz` returns `"ok"` even when dependencies are broken. This is intentional and out of scope per the spec, but operators should not treat it as a readiness check.

VERDICT: APPROVE

## Pipeline

- Implement attempts: 1 of 3
- Model cost reported by Claude Code across all stages: $0.98
- Artifacts: `.factory/1/` (issue, spec, plan, implement, test, gates.json, review, state.json)

Closes #1
