Automated change by **backend-factory** for issue #4. It was not merged and will not be: a human decides.

## Summary

Add a new read-only endpoint, `GET /customers/{customerId}/total`. It returns how much one customer has spent across all their stored orders, in cents. The response is `200` with a JSON body `{"customerId":"<id>","totalCents":<sum>}`. `<sum>` is the sum of `quantity × unitPriceCents` over every order with that `customerId`. A customer with no orders gets `totalCents` 0, not a 404.

## Verified

Deterministic gates (`java-maven`), run by the orchestrator after the last implement attempt:

- [x] **build**: `gates/java-maven.sh build examples/java-service` (1.1s)
- [x] **lint**: `gates/java-maven.sh lint examples/java-service` (1.8s)
- [x] **arch**: `gates/java-maven.sh arch examples/java-service` (2.1s)
- [x] **test**: `gates/java-maven.sh test examples/java-service` (2.2s)
- [x] **scope**: every stage changed only paths it is allowed to (gate config, agents and CI are off limits)
- [x] **size**: 314 changed lines outside `.factory/` (cap 400) in 7 files
- [x] **review agent**: `VERDICT: APPROVE` (full text in `.factory/4/review.md`)

## Not verified

- Behaviour outside what the tests exercise (no runtime, load or integration environment is started).
- Security beyond the reviewer agent's reading of the diff (no SAST, dependency or secret scanning).
- Whether the spec matches what the issue author actually wanted; the spec agent's interpretation is in the artifacts.
- Test quality: the gates prove the tests pass, not that they would catch a regression.

From the reviewer:

- **Encoded slashes in the ID:** `getRequestURI().getPath()` decodes the path. An ID containing `%2F` (e.g. `/customers/a%2Fb/total`) becomes extra path segments and returns 404 instead of a total. I reasoned this from the JDK API and did not run it. Today's `/orders/{id}` behaves the same way, and no spec criterion covers it.
- **How slow the full scan gets:** `totalCentsFor` reads every order on each request. I did not measure this with a large number of orders.
- **Gate results:** I relied on `gates.json` and the test counts reported in `.factory/4/test.md` (25 tests). I did not run Maven myself.
- **Working tree check blocked:** my command to read files and run `git status` from inside `examples/java-service` was denied, because it needed approval and this session cannot grant it. I read the needed files with the file reader instead, so I did not check for uncommitted files outside the diff.

## Risks

- **Clients that read the old 404 for `/customers`:** `GET /customers` and other `/customers...` paths now return the JSON `{"error":"no route"}` 404 instead of the HTTP server's default 404 page. AC7 asks for this. It only matters if a client relied on the old response; I judge that unlikely.
- **Server startup refactor:** `main` now calls `createServer(port).start()`. The `PORT` variable and the "listening on" log line are unchanged. I judge the chance of a regression very low.
- **Load:** each total request reads and copies the whole in-memory repository while holding its lock. This is fine at example scale. A large dataset or a real persistence backend would need a query that filters by customer. I judge this unlikely to matter for this service.

## Pipeline

- Implement attempts: 1 of 3
- Model cost reported by Claude Code across all stages: $1.35
- Artifacts: `.factory/4/` (issue, spec, plan, implement, test, gates.json, review, state.json)

Closes #4
