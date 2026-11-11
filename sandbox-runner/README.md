# sandbox-runner

A Java 21 library that runs command-line coding agents in isolated sandboxes, one branch per task, and brings their commits back by merge. It is part of [backend-factory](../README.md).

The concepts come from [Sandcastle](https://github.com/mattpocock/sandcastle), a TypeScript library: sandbox providers, branch strategies, a single `run` call. The API here is designed for Java, and no Sandcastle code was ported.

## Why Java

backend-factory targets JVM and Go backends. Teams on the JVM often want agent runs **inside their own service**: a Spring Boot app that takes a ticket, runs an agent against a repository, and decides what to do with the result. They do not want to shell out to a Node tool for that. For those teams the library offers:

- plain Java 21 with virtual threads for parallel runs;
- no Spring in the core, and one runtime dependency (Jackson);
- an optional Spring Boot starter;
- a small CLI for terminals and for the Go orchestrator, which reads its progress events as JSON lines.

## Usage

```java
SandboxRunner runner = SandboxRunner.builder()
    .provider(DockerProvider.image("ghcr.io/acme/agent-jdk21@sha256:…"))
    .agent(ClaudeCode.headless().maxTurns(40).maxBudgetUsd(3))
    .credential(Secret.fromEnv("ANTHROPIC_API_KEY"))
    .network(NetworkPolicy.allow("api.anthropic.com", "repo.maven.apache.org"))
    .limits(Limits.of(2.0, DataSize.ofGigabytes(4), Duration.ofMinutes(20)))
    .gate(Gate.command("verify", "mvn", "-B", "-q", "verify"))
    .reviewer(HttpReviewer.at(URI.create("http://localhost:8080/api/review")))
    .listener(e -> log.info("{}", e))
    .build();

Task task = Task.of("Paginate GET /orders", Path.of("."), BranchStrategy.newBranchAndMerge("feature/orders"));
RunResult one = runner.run(task);
List<RunResult> all = runner.runAll(List.of(task, other), 4);   // virtual threads, at most 4 at a time
if (one.status() == RunStatus.MERGED) log.info("{} commits on {}", one.commits().size(), one.branch().orElseThrow());
```

For the defaults (Docker, the reference image, Claude Code, network limited to `api.anthropic.com`), use `Sandbox.run(task)`.

The reference agent image (JDK 21, Maven, git, Claude Code 2.1.322) is in `docker/agent-jdk21`:

```sh
docker build -t sandbox-runner/agent-jdk21:0.1 docker/agent-jdk21
```

### CLI

```sh
mvn -B package -DskipTests
java -jar sandbox-runner-cli/target/sandbox-runner.jar run \
  --repo ../examples/java-service --task "Paginate GET /orders" --strategy merge:feature/orders \
  --credential ANTHROPIC_API_KEY --allow api.anthropic.com --allow repo.maven.apache.org \
  --gate "verify=mvn -B -q verify" --timeout 30m --events jsonl
java -jar sandbox-runner-cli/target/sandbox-runner.jar reap --repo .
```

The CLI behaves like this:
- It prints the `RunResult` as JSON on stdout.
- With `--events jsonl`, it writes one JSON object per progress event on stderr.
- Exit codes: `0` for `MERGED`, `BRANCH_READY` or `PATCH_READY`; `3` for any other final status; `2` for a usage error; `1` for an internal error.

### Spring Boot

Add `sandbox-runner-spring-boot-starter` and configure:

```yaml
sandbox-runner:
  image: ghcr.io/acme/agent-jdk21@sha256:…
  credentials: [ANTHROPIC_API_KEY]
  allow: [api.anthropic.com, repo.maven.apache.org]
  memory: 4GB
  timeout: 20m
  gates:
    verify: mvn -B -q verify
  reviewer-url: http://localhost:8080/api/review
```

A `SandboxRunner` bean appears. Your own `SandboxProvider`, `AgentRunner` or `Reviewer` beans replace the ones built from properties, and every `RunListener` and `Gate` bean is added. A credential listed in properties but missing from the environment fails startup.

## Concepts

| Interface | Implementations | Notes |
|---|---|---|
| `SandboxProvider` | `DockerProvider.image(…)`, `DockerProvider.podman(…)`, `LocalProvider.unsafeForTestsOnly()` | Docker and Podman go through their CLIs. Local has no isolation; see below. |
| `BranchStrategy` | `newBranchAndMerge(target)`, `worktree()`, `patchOnly()` | Where the agent works and how the result returns. |
| `AgentRunner` | `ClaudeCode.headless()`, `CommandAgent.of(…)` | Any CLI agent works. The task text goes in `SANDBOX_TASK` for `CommandAgent`. |
| `Gate` | `Gate.command(name, cmd…)` | Runs **inside the sandbox** after the agent. Red stops the run. |
| `Reviewer` | `HttpReviewer.at(uri)` | ai-code-reviewer contract: `POST /api/review {content, isDiff: true}` → `{verdict, summary, findings}`. `REQUEST_CHANGES` blocks. |
| `RunListener` | your code | Events: Started, WorkspaceReady, SandboxCreated, AgentStarted, AgentFinished, ChangesCollected, GateFinished, ReviewFinished, Delivered, CleanedUp, Finished. |

### Branch strategies

| Strategy | Agent works in | Agent can commit | Result | Host repository |
|---|---|---|---|---|
| `newBranchAndMerge(target)` | private clone on `sandbox/<run>` | yes | merged into `target` (`--no-ff`) | gets the merge; temp branch deleted |
| `worktree()` | host worktree, files only | no; the library commits for it | `sandbox/<run>` branch left for a human | new branch |
| `patchOnly()` | private clone | yes | diff in `RunResult` | untouched (refs and objects) |

## Lifecycle of a run

```
run(task)
 0. target branch protected? ──yes──► REFUSED_PROTECTED (nothing created)
 1. WORKSPACE   private clone (no hardlinks, origin removed) or worktree     host git, hardened
 2. SANDBOX     docker run --network none|internal+proxy --cap-drop ALL --security-opt no-new-privileges
                --user <host uid> --read-only --tmpfs /tmp,/home/sandbox --cpus --memory(=swap) --pids-limit
                mounts: workspace (+ /out for the result bundle); never the host repository
                PID 1: sleep <timeout + 5 min>  ·  watchdog: deadline → docker kill → TIMED_OUT
 3. AGENT       claude -p … --output-format json  (or any command)
                died → SANDBOX_DIED · non-zero / is_error → AGENT_FAILED
 4. COLLECT     clone: in the sandbox, commit leftovers + git bundle → host fetches the bundle into a
                       scratch repo it created (host objects borrowed read-only via alternates)
                worktree: verify .git pointer, no nested .git, no new/changed .gitattributes/.gitmodules,
                          then commit on the host
                nothing new → NO_CHANGES · history not descending from base → POLICY_VIOLATION
 5. POLICY      no .gitattributes/.gitmodules/.github/**, no submodules, diff ≤ maxDiff,
                no credential in the diff → else POLICY_VIOLATION
 6. GATES       inside the sandbox, in order, first red → GATE_FAILED
 7. REVIEW      optional HTTP reviewer; REQUEST_CHANGES or no verdict → REVIEW_BLOCKED
 8. DELIVER     merge: re-check protection; fetch into sandbox/<run>; merge in the target's worktree
                       (refused if it has local changes → TARGET_BUSY) or a temporary one;
                       conflict → merge --abort → MERGE_CONFLICT
                worktree → BRANCH_READY · patch → PATCH_READY
 9. CLEANUP     always, in reverse order: watchdog, container, proxy, network, temp worktree,
                temp branch (unless delivered, or retainOnConflict), temp directory
```

## Security model

**Enforced by the library**

- **The host repository is never mounted.** The sandbox gets a private clone, or the files of a worktree with its `.git` pointer mounted read-only. An integration test inspects the container's mounts.
- **The host never runs git inside a directory the agent controlled** (with one exception below). Results come back as a git bundle that the host fetches into a scratch repository it created itself. Every host git call disables hooks and fsmonitor and forbids all transports except local files. The exception: in the worktree strategy, git runs in the worktree only after the integrity checks of step 4.
- **Credentials:**
  - `Secret` values reach the container as environment variables passed through the CLI's environment, never on its command line.
  - Their raw, base64 and URL-encoded forms are masked in logs, events, gate output and `RunResult`.
  - A diff that contains one is a `POLICY_VIOLATION`, so a credential cannot be committed.
- **Network:**
  - `none` by default.
  - `allow(...)` puts the sandbox on an internal Docker network whose only exit is a tinyproxy container. The proxy accepts clients only from that subnet, filters by domain, and allows CONNECT only to port 443.
  - Direct connections have no route. An integration test checks the allowed domain, a non-listed domain and a direct connection.
- **Limits:**
  - CPU, memory (no swap) and PID limits are applied.
  - A whole-run deadline kills the container.
  - The container's own `sleep` ends shortly after the deadline, even if the JVM died.
  - `reap` removes expired containers and networks by label.
- **No push, no protected branches:**
  - No code path pushes, and the clone has no remote.
  - Protected targets (`main`, `master`, `develop`, `trunk`, `release/*`, `release-*`, `hotfix/*` by default, or your own `BranchProtection`) are refused before anything runs, and checked again right before the merge.
- **Gates run in the sandbox**, because they build and test code the agent wrote.
- **The reviewer fails closed.** A timeout, a 413, a 5xx or an unparseable body blocks the run unless `failOpen()` is set. Plain HTTP is accepted only for localhost.

**Not protected (see Limits)**

- **Docker shares the host kernel.** This is not a VM, and a kernel or runtime escape defeats every rule above.
- **Exfiltration through allowed domains.** An agent hijacked by prompt injection can still send data, including its own credential, to any domain on the allowlist.
- **`docker inspect` shows the container's environment** to anyone with access to the daemon, and access to the daemon is root-equivalent on the host.
- **Code merged into your branch runs wherever you build it next.** Gates and the reviewer reduce that risk; they do not remove it. A human still has to review.

## Tests

| Suite | Command | What it covers | Run so far |
|---|---|---|---|
| Unit | `mvn verify` | Strategies on temporary git repositories (merge, conflict with and without retention, dirty target, re-checked protection, rewritten history, patch-only leaves no objects, worktree integrity attacks); runner (gate red, review block, timeout, policy, parallel `runAll` into one target, listener failure); reviewer against a stub HTTP server; CLI parsing; Spring auto-configuration | 40 tests, green locally and in CI |
| Docker | `mvn verify -Pdocker` | Real containers with a fake agent (a shell script that writes a file and commits): merge, worktree, patch, timeout, red gate, merge conflict, container killed mid-run, secret redaction and the commit block, network off, allowlist, host repo not mounted, nothing left behind; CLI end to end with events | 11 more tests, green locally and in CI (GitHub `ubuntu-latest`) |
| End to end | `SANDBOX_RUNNER_E2E=1 ANTHROPIC_API_KEY=… mvn test -Pe2e` | The real Claude Code in the reference image writes and commits a file, which is merged | **Not run yet.** No API key or OAuth token was available to pass into a container. |

The reference image was smoke-tested with the provider's restrictions: host uid, read-only root, tmpfs home, all capabilities dropped, no network. Inside it, `claude --version` printed 2.1.322 and `java`, `mvn` and `git` all ran. That is the only check made with Claude Code inside the sandbox.

## Limits

- **Claude Code has not completed a task inside this sandbox yet.** The end-to-end test exists but has not run. In particular, it is unverified whether Claude Code honours `HTTPS_PROXY` through the egress proxy, and whether it needs domains beyond `api.anthropic.com` with `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`.
- **Podman is untested.** `DockerProvider.podman(…)` passes the same flags to `podman`, but Podman was not installed where this was built. Rootless Podman may need `--userns=keep-id` for file ownership on the mounts.
- **Linux only.** The host uid is read through `unix:uid`, and macOS and Windows were not tried.
- **The allowlist covers HTTP(S) clients that honour `HTTPS_PROXY`.** Others get no network at all (fail closed). Filtering is by the name in CONNECT, so domain fronting through an allowed CDN is not prevented.
- **Disk is not limited.** The workspace is a bind mount, and memory and CPU limits do not cap what the agent writes there.
- **Every run clones the whole repository.** The clone uses `--no-hardlinks`, so large repositories cost time and disk per run.
- **No live agent output.** `AgentFinished` carries a summary when the agent ends, but nothing streams line by line.
- **Redaction is by value.** A credential transformed in any way other than base64 or URL encoding is not masked.
- **Merges are authored by `sandbox-runner`, and merging into a checked-out branch updates that working tree.** It is refused when the tree has local changes.
- **Parallel runs into one target merge one at a time.** Two tasks that touch the same lines: the second gets `MERGE_CONFLICT`.
- **`reap` does not delete `sandbox/*` branches**, because a delivered worktree result is one. Delete those yourself.
- **No performance figures are given** because none were measured.
