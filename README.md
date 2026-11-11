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

<!-- RESULTS -->

## Limits

<!-- LIMITS -->
