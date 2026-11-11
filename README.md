# backend-factory

Takes an issue to a pull request with Claude Code in headless mode. It is opinionated for Java (Maven or Gradle) and Go backends.

Each stage runs as a separate headless Claude Code session with its own subagent definition and the smallest tool set that stage needs. A small Go orchestrator decides what runs next. Deterministic gates (build, lint, architecture rules, tests) decide whether the change can leave. The model's opinion never does. The pipeline opens a pull request and stops there: it never merges and never writes to a protected branch.

## Flow

```
 GitHub issue  ──label "factory"──►  .github/workflows/factory.yml ─┐
 Linear issue  ──label webhook────►  factory serve ─(repository_dispatch)─┤
                                                                    ▼
                                         factory run   (Go orchestrator)
                                         │ git worktree on factory/<issue>-<slug>
                                         ▼
 ┌──────────── claude -p --agent <stage> --output-format json ──────────────┐
 │ spec       Read/Grep/Glob             → .factory/<issue>/spec.md          │
 │   └─ STATUS: NEEDS_HUMAN ─────────────────────────────► STOP (human)     │
 │ plan       Read/Grep/Glob             → plan.md                          │
 │ implement  + Edit/Write + build cmds  → code, implement.md               │◄──┐
 │ test       + test files only          → tests, test.md                   │   │
 └──────────────────────────────────────────────────────────────────────────┘   │
   after every stage: scope check, reverting out-of-scope files (red)          │
                                         ▼                                      │
   GATES (scripts in gates/, run by the orchestrator → gates.json)             │
     Go:    go build + vet · golangci-lint · depguard layer rules · go test    │
     Java:  compile · Checkstyle · ArchUnit · tests (Maven; Gradle script too) │
          red, attempt < 3 ── gate output written to feedback.md ──────────────┤
          diff > size cap ─────────────────────────────────► STOP (human)      │
                                         ▼ green                                │
 review     Read/Grep/Glob + git diff    → review.md                            │
          VERDICT: REQUEST_CHANGES, attempt < 3 ── findings to feedback.md ────┘
          attempt = 3 and still red ───────────────────────► STOP (human)
                                         ▼ VERDICT: APPROVE
   orchestrator pushes factory/* (refuses protected branches), gh pr create
   PR body: what was verified, what was not, risks. No merge code path exists.
                                         ▼
   .factory/metrics.jsonl  ◄── every transition: stage, attempt, turns, cost, outcome
```

A STOP comments on the issue, adds `factory:needs-human` and pushes the `factory/*` branch with all artifacts so a person can pick it up.

## How it is put together

| Piece | Where | Notes |
|---|---|---|
| Orchestrator | `cmd/factory`, `internal/pipeline` | State, attempts and metrics in plain Go. `factory run`, `factory metrics`, `factory serve`. |
| Stage runner | `internal/stage` | `claude -p --agent <stage> --output-format json --permission-prompts none --max-turns N --max-budget-usd X --allowedTools …` |
| Subagents | `.claude/agents/{spec,plan,implement,test,review}.md` | One per stage. `tools:` in the frontmatter removes tools; `--allowedTools` decides which concrete commands may run. |
| Gates | `gates/{go,java-maven,java-gradle}.sh`, `internal/gates` | Same scripts in the pipeline, the Stop hook and CI. Run in order build → lint → arch → test and stop at the first red. |
| Scope check | `internal/gates/scope.go` | spec, plan and review may change nothing. implement may change only the service. test may change only test files. No stage may touch `.claude/`, `gates/`, `.github/`, `.golangci.yml`, `checkstyle.xml` or `ArchitectureTest.java`. Violations are reverted and count as a failed attempt. |
| Hooks | `.claude/settings.json`, `.claude/hooks/` | PreToolUse denies `gh pr merge`, force pushes and, inside a stage, git writes and network tools. A Stop hook sends implement/test back once if the build is broken. |
| Git/GitHub | `internal/vcs` | Writes only to `factory/*`. Asks GitHub whether a branch is protected before pushing and refuses if the answer is yes or unknown. No merge function. |
| Triggers | `.github/workflows/factory.yml`, `internal/trigger` | Issue label, or Linear webhook (HMAC-verified, one-minute replay window) forwarded as `repository_dispatch`. |

### Decisions that came from the Claude Code docs

- **Hooks in subagent frontmatter do not run in `claude -p`.** A headless session does not count as a trusted workspace, so those hooks are skipped. The hooks here live in `.claude/settings.json`, which does load in `-p`. The decisive checks are not hooks at all: the orchestrator runs them.
- **`-p` starts in Manual permission mode** (Claude Code 2.1.302+), so anything that would prompt is denied. Each stage passes an explicit `--allowedTools` list and `--permission-prompts none`.
- **`--agent <name>` makes the whole session that agent**, with the frontmatter `tools:` applied. Checked against the real CLI: the `spec` agent reports it has no Bash even when `--allowedTools 'Bash(echo *)'` is passed. A PreToolUse deny from `settings.json` showed up in `permission_denials` of a real `-p --agent implement` run.
- **A blocking Stop hook has a cap of eight continuations in a row**, so it cannot serve as the retry loop. The three-attempt loop is in Go.
- **`anthropics/claude-code-action@v1` with a `prompt` runs one session.** That does not fit a staged pipeline with gates between stages. The workflow installs the CLI and runs the orchestrator.

## Running it

Requirements: Go, git, `gh` (authenticated), the `claude` CLI (authenticated), plus the service toolchain (golangci-lint v2 for Go; JDK 21 and Maven for Java).

```sh
go build -o bin/factory ./cmd/factory
./bin/factory run --issue 12               # GitHub issue with a service:<name> label
./bin/factory run --issue 12 --remote=false  # no push, no PR: PR body lands in .factory/12/pr.md
./bin/factory metrics --refresh            # also asks GitHub which PRs a human merged
```

In GitHub Actions: add an `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` secret, then add the `factory` label to an issue that also has a `service:<name>` label. Without a credential the workflow skips with a notice.

Exit codes of `factory run`: `0` PR opened, `3` stopped for a human, anything else is a pipeline error.

## Results on the five example issues

Five issues were opened on this repository and each was run once with `factory run --issue N` on 2026-11-11, using Claude Code 2.1.322 with its default model. Every number below comes from [`docs/metrics-2026-11-11.jsonl`](docs/metrics-2026-11-11.jsonl), the raw event log, via `factory metrics`. Cost is the `total_cost_usd` that Claude Code reports. These runs used a claude.ai subscription login, so it is Claude Code's estimate, not an amount billed.

| Issue | Service | Outcome | Stopped at | Implement attempts | Turns | Cost | Minutes |
|---|---|---|---|---|---|---|---|
| [#1](https://github.com/dev-danilocordeiro/backend-factory/issues/1) Add `GET /healthz` with the build version | Go | PR [#7](https://github.com/dev-danilocordeiro/backend-factory/pull/7) | | 1 | 42 | $0.80 | 2.5 |
| [#2](https://github.com/dev-danilocordeiro/backend-factory/issues/2) `POST /orders` accepts zero or negative quantity | Go | PR [#8](https://github.com/dev-danilocordeiro/backend-factory/pull/8) | | 1 | 47 | $0.92 | 2.7 |
| [#3](https://github.com/dev-danilocordeiro/backend-factory/issues/3) Paginate `GET /orders` | Java | needs human | gates (size: 407 lines, cap 400) | 1 | 59 | $1.05 | 3.8 |
| [#4](https://github.com/dev-danilocordeiro/backend-factory/issues/4) Show the customer total | Java | PR [#9](https://github.com/dev-danilocordeiro/backend-factory/pull/9) | | 1 | 52 | $1.35 | 4.0 |
| [#5](https://github.com/dev-danilocordeiro/backend-factory/issues/5) Make the order service faster | Java | needs human | spec (`NEEDS_HUMAN`) | 0 | 6 | $0.07 | 0.2 |

| Metric | Value |
|---|---|
| Issues received | 5 |
| PRs opened | 3 |
| PRs accepted (merged by a human) | 0 so far. No one has reviewed them yet. `factory metrics --refresh` updates this. |
| Stopped for a human | 2 (spec: 1, gates: 1) |
| Pipeline errors | 0 |
| Turns / cost, counted runs | 206 / $4.20 |

Issue #1 ran twice. The first run opened PR [#6](https://github.com/dev-danilocordeiro/backend-factory/pull/6) (38 turns, $0.98). I closed it because its body and `gates.json` contained absolute paths from my machine. After the fix (e68cdca) I re-ran the issue. The table counts the second run; the raw log has both. With the discarded run included, the totals are 244 turns and $5.17.

What the runs showed, including what did not go well:

- **The architecture rule held without a red gate.** Issue #4 suggests a layering violation on purpose ("the controller can just read all orders from the repository"). The spec and plan agents put the sum in `OrderService`, and the controller calls only the service. ArchUnit stayed green because nothing violated it. So in these runs the architecture gate never failed for real. Its red path is covered only by the deliberate violation checks I made while building the examples.
- **The spec gate stopped the vague issue cheaply.** #5 cost $0.07. The spec lists the questions a human has to answer: which operation, what load, which metric, what target.
- **The retry loop was never exercised against the real model.** Every run that reached the gates passed them on attempt 1, and no review asked for changes. The loop, the feedback file and the three-attempt stop are covered by unit tests with a scripted model (`internal/pipeline/pipeline_test.go`), not by these runs.
- **The PRs are mostly tests.** Production lines vs test lines (outside `.factory/`): PR #7 12 vs 231, PR #8 5 vs 182, PR #9 72 vs 242. Issue #3 hit the size cap with 96 production lines and 304 test lines. The cap counts test code, which is debatable; it was not changed to let #3 through.
- **The reviewer missed scope creep in PR #8.** The implement agent also changed `GET /orders` to return `[]` instead of `null` for an empty list. The change is harmless, but the issue did not ask for it, and the review approved it without comment.
- **The test agent duplicated a gate.** In PR #8 it added `imports_test.go` files that re-check the depguard rules already enforced by the arch gate.
- **Allowlist friction.** The PR #8 test agent reports that a command it used to look for `golangci-lint` was denied, because only listed commands may run, so it ran `go vet` instead. The orchestrator ran the real lint gate afterwards.


## Limits

- **Five issues on two toy services is not evidence of general usefulness.** The services are small, in memory and written for this repo. The results above describe these runs and nothing more.
- **Not exercised end to end:** the GitHub Actions trigger (no model credential is stored in this repo, so the workflow skips) and the Linear trigger. The Linear webhook code is tested only with recorded payloads built from the public docs. Those docs do not say how labels appear in an Issue payload, so `data.labels[]` and `updatedFrom.labelIds` are assumptions.
- **Gradle is supported but untested.** `gates/java-gradle.sh` exists and stack detection recognises `build.gradle[.kts]`, but no Gradle project ran through it. Gradle was not installed on the machine that ran the examples.
- **No sandbox.** Stages run on the host with the user's permissions. The allowlist, the hooks and the scope check limit what the model asks for, but `go test` and `mvn` execute code the model wrote. Run it on a disposable runner, not on a laptop with secrets.
- **`Bash(mvn *)` is broad.** Maven plugins can run arbitrary code. It is allowed because the implement and test stages need it.
- **Markers are text.** The `STATUS:` and `VERDICT:` lines are parsed from the agent's final message. A missing marker is treated as the conservative outcome (not ready, request changes), but a wrong marker is believed.
- **The size cap counts tests.** It counts every changed line outside `.factory/`, which is what stopped issue #3.
- **Re-running an issue whose `factory/*` branch is already on the remote fails at push.** The pipeline never force-pushes. Delete the branch or close the PR first.
- **Concurrent runs** are separated by worktree and by the workflow `concurrency` group. Two local `factory run` processes for different issues were not tested in parallel.
- **"PRs accepted" depends on a human merging.** The pipeline cannot and will not merge, so this number stays at zero until someone does.
- **PRs opened with the default `GITHUB_TOKEN` do not trigger other workflows**, so CI will not run on them unless `FACTORY_GH_TOKEN` is set.
- **Hooks depend on the Claude Code version.** The workflow pins 2.1.322, the version these runs used. Frontmatter hooks do not run in `-p`, which is why every decisive check lives in the orchestrator.
