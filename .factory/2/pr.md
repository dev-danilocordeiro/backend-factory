Automated change by **backend-factory** for issue #2. It was not merged and will not be: a human decides.

## Summary

Right now `POST /orders` stores an order whose `quantity` is zero or negative and answers `201 Created`. After this change, the service rejects any order with `quantity` below 1. The response is `400 Bad Request` with a JSON error body, and nothing is stored. The rule is enforced by `order.Service.Create`, so it applies through HTTP and through the public Go function alike.

## Verified

Deterministic gates (`go`), run by the orchestrator after the last implement attempt:

- [x] **build**: `gates/go.sh build examples/go-service` (0.3s)
- [x] **lint**: `gates/go.sh lint examples/go-service` (0.5s)
- [x] **arch**: `gates/go.sh arch examples/go-service` (0.1s)
- [x] **test**: `gates/go.sh test examples/go-service` (0.2s)
- [x] **scope**: every stage changed only paths it is allowed to (gate config, agents and CI are off limits)
- [x] **size**: 187 changed lines outside `.factory/` (cap 400) in 6 files
- [x] **review agent**: `VERDICT: APPROVE` (full text in `.factory/2/review.md`)

## Not verified

- Behaviour outside what the tests exercise (no runtime, load or integration environment is started).
- Security beyond the reviewer agent's reading of the diff (no SAST, dependency or secret scanning).
- Whether the spec matches what the issue author actually wanted; the spec agent's interpretation is in the artifacts.
- Test quality: the gates prove the tests pass, not that they would catch a regression.

From the reviewer:

- I could not run `go test ./...` here, because running commands needed approval and this session cannot grant it. I relied on `gates.json`, which reports build, lint, arch and test all green.
- `.factory/2/test.md` says the test stage did not run `golangci-lint`. The lint gate in `gates.json` did pass, so the lint part of criterion 11 holds if that gate uses the repo's `.golangci.yml`. I did not check the gate script.
- I did not check how other `order.Repository` implementations behave, if any exist outside this example. Only the memory store is in the repo.

## Risks

- Clients that send `quantity` ≤ 0, or leave it out, now get 400 instead of 201. This is intended. Callers that relied on the old default of 0 will break, which is moderately likely for any existing integration.
- `GET /orders` on an empty service now returns `[]` instead of `null`. A client that checks for `null` would see a different body. I judge this unlikely, and the spec requires an array.
- A rejected quantity gets the same `"invalid order"` message as a missing field, so clients cannot tell which field failed. The spec accepts this.

## Pipeline

- Implement attempts: 1 of 3
- Model cost reported by Claude Code across all stages: $0.92
- Artifacts: `.factory/2/` (issue, spec, plan, implement, test, gates.json, review, state.json)

Closes #2
