Automated change by **backend-factory** for issue #1. It was not merged and will not be: a human decides.

## Summary

Load balancers need a health endpoint, so the service gets a new `GET /healthz` route. It returns `200 OK` with the JSON body `{"status":"ok","version":"<version>"}`. The version comes from a package-level string variable that the build can set with `go build -ldflags "-X <import path>.<name>=<value>"`. If the build does not set it, the version is `"dev"`. The existing `/orders` routes do not change.

## Verified

Deterministic gates (`go`), run by the orchestrator after the last implement attempt:

- [x] **build**: `gates/go.sh build examples/go-service` (0.4s)
- [x] **lint**: `gates/go.sh lint examples/go-service` (0.8s)
- [x] **arch**: `gates/go.sh arch examples/go-service` (0.1s)
- [x] **test**: `gates/go.sh test examples/go-service` (0.6s)
- [x] **scope**: every stage changed only paths it is allowed to (gate config, agents and CI are off limits)
- [x] **size**: 243 changed lines outside `.factory/` (cap 400) in 5 files
- [x] **review agent**: `VERDICT: APPROVE` (full text in `.factory/1/review.md`)

## Not verified

- Behaviour outside what the tests exercise (no runtime, load or integration environment is started).
- Security beyond the reviewer agent's reading of the diff (no SAST, dependency or secret scanning).
- Whether the spec matches what the issue author actually wanted; the spec agent's interpretation is in the artifacts.
- Test quality: the gates prove the tests pass, not that they would catch a regression.

From the reviewer:

- I did not run the tests or lint myself. I relied on `gates.json`, which shows all gates green.
- `.factory/1/test.md` says the tester could not run `golangci-lint` with the service's own `.golangci.yml`. I assume the green lint gate (`gates/go.sh lint examples/go-service`) did use that config, but I did not check this.
- I could not confirm that the lint and test gates run without `-short`, which is needed for the ldflags end-to-end test to run in CI.
- I did not test how real load balancers behave against the endpoint, such as their timeouts or whether they send HEAD or GET.

## Risks

- **Flaky end-to-end test:** the port race and the dependence on the `go` tool could occasionally fail CI. This is unlikely and affects only developers and CI, not production.
- **Silent version loss:** if `Version` is later moved or renamed, build scripts using the old `-X` path will quietly produce `"dev"`, because the Go linker ignores unknown `-X` targets. The source test and the ldflags test reduce this risk. It is low likelihood and affects operators who read the version.
- **Public version exposure:** `/healthz` has no authentication and shows the build version to anyone who can reach it. The spec puts authentication out of scope and this is common practice, so the risk is low.

## Pipeline

- Implement attempts: 1 of 3
- Model cost reported by Claude Code across all stages: $0.80
- Artifacts: `.factory/1/` (issue, spec, plan, implement, test, gates.json, review, state.json)

Closes #1
