package com.danilocordeiro.sandboxrunner;

import com.danilocordeiro.sandboxrunner.agent.AgentReport;
import com.danilocordeiro.sandboxrunner.agent.AgentRequest;
import com.danilocordeiro.sandboxrunner.agent.AgentResult;
import com.danilocordeiro.sandboxrunner.agent.AgentRunner;
import com.danilocordeiro.sandboxrunner.event.RunEvent;
import com.danilocordeiro.sandboxrunner.event.RunListener;
import com.danilocordeiro.sandboxrunner.gate.Gate;
import com.danilocordeiro.sandboxrunner.gate.GateResult;
import com.danilocordeiro.sandboxrunner.git.BranchProtection;
import com.danilocordeiro.sandboxrunner.git.Delivery;
import com.danilocordeiro.sandboxrunner.git.Git;
import com.danilocordeiro.sandboxrunner.git.HostRepo;
import com.danilocordeiro.sandboxrunner.git.Imported;
import com.danilocordeiro.sandboxrunner.git.PolicyException;
import com.danilocordeiro.sandboxrunner.git.StrategyContext;
import com.danilocordeiro.sandboxrunner.git.Workspace;
import com.danilocordeiro.sandboxrunner.internal.Cleanup;
import com.danilocordeiro.sandboxrunner.internal.Redactor;
import com.danilocordeiro.sandboxrunner.review.ReviewOutcome;
import com.danilocordeiro.sandboxrunner.review.Reviewer;
import com.danilocordeiro.sandboxrunner.spi.SandboxDiedException;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import com.danilocordeiro.sandboxrunner.spi.SandboxProvider;
import com.danilocordeiro.sandboxrunner.spi.SandboxSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Runs tasks: prepare a workspace, start a sandbox, run the agent, collect its
 * commits, check policy, gates and review, deliver, and always clean up.
 * Instances are immutable and thread-safe; reuse one for many runs.
 */
public final class SandboxRunner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LOG_TAIL = 8000;

    private final SandboxProvider provider;
    private final AgentRunner agent;
    private final List<Secret> credentials;
    private final NetworkPolicy network;
    private final Limits limits;
    private final List<Gate> gates;
    private final Optional<Reviewer> reviewer;
    private final List<RunListener> listeners;
    private final BranchProtection protection;
    private final ChangePolicy changePolicy;
    private final boolean retainOnConflict;
    private final Map<String, String> env;

    private SandboxRunner(Builder b) {
        this.provider = Objects.requireNonNull(b.provider, "provider");
        this.agent = Objects.requireNonNull(b.agent, "agent");
        this.credentials = List.copyOf(b.credentials);
        this.network = b.network;
        this.limits = b.limits;
        this.gates = List.copyOf(b.gates);
        this.reviewer = Optional.ofNullable(b.reviewer);
        this.listeners = List.copyOf(b.listeners);
        this.protection = b.protection;
        this.changePolicy = b.changePolicy;
        this.retainOnConflict = b.retainOnConflict;
        this.env = Map.copyOf(b.env);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Runs tasks in parallel on virtual threads, at most maxConcurrency at a time. Results keep task order. */
    public List<RunResult> runAll(List<Task> tasks, int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        Semaphore permits = new Semaphore(maxConcurrency);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RunResult>> fs = new ArrayList<>();
            for (Task t : tasks) {
                fs.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        return run(t);
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<RunResult> out = new ArrayList<>();
            for (Future<RunResult> f : fs) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    throw new IllegalStateException("run() must not throw", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            return out;
        }
    }

    /** Runs one task. Never throws for run failures: they are in {@link RunResult#status()}. */
    public RunResult run(Task task) {
        return new Run(task).execute();
    }

    /**
     * Removes what crashed runs left behind: provider resources (containers,
     * networks) and stale worktree registrations in repo. Branches are left
     * alone because a sandbox/* branch may be a delivered result.
     */
    public int reap(Path repo) {
        int n = provider.reapOrphans();
        Git.tryRun(HostRepo.open(repo).root(), "worktree", "prune");
        return n;
    }

    /** State of one run. */
    private final class Run {
        private final Task task;
        private final String runId = "r" + HexFormat.of().formatHex(RANDOM.generateSeed(6));
        private final Instant start = Instant.now();
        private final Instant deadline = start.plus(limits.timeout());
        private final Redactor redactor = new Redactor(credentials);
        private final Cleanup cleanup = new Cleanup();
        private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private RunStatus status = RunStatus.ERROR;
        private String detail = "";
        private Optional<String> branch = Optional.empty();
        private List<com.danilocordeiro.sandboxrunner.CommitInfo> commits = List.of();
        private String diff = "";
        private final List<GateResult> gateResults = new ArrayList<>();
        private Optional<ReviewOutcome> review = Optional.empty();
        private Optional<AgentReport> agentReport = Optional.empty();

        Run(Task task) {
            this.task = task;
        }

        RunResult execute() {
            emit(new RunEvent.Started(runId, Instant.now(), redactor.redact(task.title()), task.strategy().name()));
            try {
                steps();
            } catch (PolicyException e) {
                finish(RunStatus.POLICY_VIOLATION, e.getMessage());
            } catch (SandboxDiedException e) {
                finish(timedOut.get() ? RunStatus.TIMED_OUT : RunStatus.SANDBOX_DIED,
                        timedOut.get() ? "time limit of " + limits.timeout() + " exceeded" : e.getMessage());
            } catch (RuntimeException e) {
                finish(timedOut.get() ? RunStatus.TIMED_OUT : RunStatus.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                finished.set(true);
                cleanup.releaseAll();
                cleanup.failures().forEach(f -> log("cleanup failed: " + f));
                emit(new RunEvent.CleanedUp(runId, Instant.now(), cleanup.released(), cleanup.failures().size()));
            }
            Duration took = Duration.between(start, Instant.now());
            emit(new RunEvent.Finished(runId, Instant.now(), status, took.toMillis()));
            return new RunResult(runId, status, redactor.redact(detail), branch, commits, redactor.redact(diff),
                    gateResults, review, agentReport, took, logs.stream().map(redactor::redact).toList());
        }

        private void finish(RunStatus s, String d) {
            status = s;
            detail = d == null ? "" : d;
        }

        private void steps() {
            Optional<String> target = task.strategy().target();
            if (target.isPresent() && protection.isProtected(target.get())) {
                finish(RunStatus.REFUSED_PROTECTED, "target branch " + target.get() + " is protected");
                return;
            }
            HostRepo repo = HostRepo.open(task.repo());
            Path tmp = tempDir();
            AtomicBoolean keep = new AtomicBoolean();
            StrategyContext ctx = new StrategyContext(repo, runId, task.title(), task.base(), tmp, cleanup, protection,
                    limits.maxDiff().bytes() * 20, retainOnConflict, keep);

            Workspace ws = locked(repo, () -> task.strategy().prepare(ctx));
            emit(new RunEvent.WorkspaceReady(runId, Instant.now(), ws.branch(), ws.baseCommit()));

            SandboxInstance sb = provider.create(new SandboxSpec(runId, ws.mounts(), ws.workdir(), env, credentials, network, limits));
            cleanup.push("destroy sandbox", sb::close);
            emit(new RunEvent.SandboxCreated(runId, Instant.now(), provider.name()));
            Thread watchdog = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(Duration.between(Instant.now(), deadline));
                    if (!finished.get()) {
                        timedOut.set(true);
                        log("time limit reached; destroying sandbox");
                        sb.kill();
                    }
                } catch (InterruptedException ignored) {
                    // run finished first
                }
            });
            cleanup.push("stop watchdog", watchdog::interrupt);

            emit(new RunEvent.AgentStarted(runId, Instant.now(), agent.name()));
            AgentResult ar = agent.run(sb, new AgentRequest(task.prompt(), ws.workdir(), remaining()));
            agentReport = ar.report();
            log("agent output (tail):\n" + tail(ar.output()));
            emit(new RunEvent.AgentFinished(runId, Instant.now(), ar.ok(), redactor.redact(firstLine(ar.output()))));
            if (timedOut.get() || ar.timedOut()) {
                finish(RunStatus.TIMED_OUT, "time limit of " + limits.timeout() + " exceeded during the agent");
                return;
            }
            if (!sb.isAlive()) {
                finish(RunStatus.SANDBOX_DIED, "sandbox stopped while the agent was running");
                return;
            }
            if (!ar.ok()) {
                finish(RunStatus.AGENT_FAILED, "agent exited with " + ar.exitCode() + ": " + firstLine(ar.output()));
                return;
            }

            Imported result = task.strategy().collect(ws, sb, remaining(), ctx);
            commits = result.commits();
            diff = result.diff();
            emit(new RunEvent.ChangesCollected(runId, Instant.now(), result.commits().size(), result.changedPaths().size()));
            if (result.isEmpty()) {
                finish(RunStatus.NO_CHANGES, "the agent made no changes");
                return;
            }
            changePolicy.check(result, redactor, limits.maxDiff());

            for (Gate g : gates) {
                GateResult gr = g.check(sb, ws.workdir(), remaining());
                gr = new GateResult(gr.name(), gr.ok(), gr.exitCode(), gr.timedOut(), redactor.redact(tail(gr.output())), gr.durationMs());
                gateResults.add(gr);
                emit(new RunEvent.GateFinished(runId, Instant.now(), gr.name(), gr.ok(), gr.durationMs()));
                if (timedOut.get()) {
                    throw new SandboxDiedException("time limit reached during gate " + gr.name());
                }
                if (!gr.ok()) {
                    finish(RunStatus.GATE_FAILED, "gate " + gr.name() + " failed with exit code " + gr.exitCode());
                    return;
                }
            }
            if (reviewer.isPresent()) {
                ReviewOutcome ro = reviewer.get().review(diff, task.title());
                review = Optional.of(ro);
                emit(new RunEvent.ReviewFinished(runId, Instant.now(), ro.verdict(), ro.blocking()));
                if (ro.blocking()) {
                    finish(RunStatus.REVIEW_BLOCKED, "review " + ro.verdict() + ": " + firstLine(ro.summary()));
                    return;
                }
            }
            Delivery d = locked(repo, () -> task.strategy().deliver(result, ctx));
            branch = d.branch();
            finish(d.status(), d.detail());
            emit(new RunEvent.Delivered(runId, Instant.now(), d.status(), d.branch().orElse("")));
        }

        private Path tempDir() {
            try {
                Path tmp = Files.createTempDirectory("sandbox-runner-" + runId + "-");
                cleanup.push("delete " + tmp, () -> deleteRecursively(tmp));
                return tmp;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Duration remaining() {
            Duration d = Duration.between(Instant.now(), deadline);
            return d.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : d;
        }

        private <T> T locked(HostRepo repo, java.util.function.Supplier<T> action) {
            ReentrantLock lock = repo.lock();
            lock.lock();
            try {
                return action.get();
            } finally {
                lock.unlock();
            }
        }

        private void log(String line) {
            logs.add(redactor.redact(line));
        }

        private void emit(RunEvent e) {
            for (RunListener l : listeners) {
                try {
                    l.onEvent(e);
                } catch (RuntimeException ex) {
                    log("listener failed: " + ex);
                }
            }
        }
    }

    static String tail(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= LOG_TAIL ? s : "…" + s.substring(s.length() - LOG_TAIL);
    }

    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        int i = t.indexOf('\n');
        t = i < 0 ? t : t.substring(0, i);
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    static void deleteRecursively(Path p) {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                x.toFile().setWritable(true);
                Files.deleteIfExists(x);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Configures a {@link SandboxRunner}. Provider and agent are required. */
    public static final class Builder {
        private SandboxProvider provider;
        private AgentRunner agent;
        private final List<Secret> credentials = new ArrayList<>();
        private NetworkPolicy network = NetworkPolicy.none();
        private Limits limits = Limits.defaults();
        private final List<Gate> gates = new ArrayList<>();
        private Reviewer reviewer;
        private final List<RunListener> listeners = new ArrayList<>();
        private BranchProtection protection = BranchProtection.defaults();
        private ChangePolicy changePolicy = ChangePolicy.defaults();
        private boolean retainOnConflict;
        private final Map<String, String> env = new java.util.HashMap<>();

        public Builder provider(SandboxProvider p) {
            this.provider = p;
            return this;
        }

        public Builder agent(AgentRunner a) {
            this.agent = a;
            return this;
        }

        public Builder credential(Secret s) {
            this.credentials.add(s);
            return this;
        }

        public Builder network(NetworkPolicy n) {
            this.network = Objects.requireNonNull(n);
            return this;
        }

        public Builder limits(Limits l) {
            this.limits = Objects.requireNonNull(l);
            return this;
        }

        public Builder gate(Gate g) {
            this.gates.add(g);
            return this;
        }

        public Builder reviewer(Reviewer r) {
            this.reviewer = r;
            return this;
        }

        public Builder listener(RunListener l) {
            this.listeners.add(l);
            return this;
        }

        public Builder protection(BranchProtection p) {
            this.protection = Objects.requireNonNull(p);
            return this;
        }

        public Builder changePolicy(ChangePolicy c) {
            this.changePolicy = Objects.requireNonNull(c);
            return this;
        }

        /** Keep the sandbox/* branch when a merge conflicts (default: delete it; the diff stays in the result). */
        public Builder retainOnConflict(boolean r) {
            this.retainOnConflict = r;
            return this;
        }

        /** Plain, non-secret environment for the sandbox. Use {@link #credential(Secret)} for secrets. */
        public Builder env(String name, String value) {
            this.env.put(name, value);
            return this;
        }

        public SandboxRunner build() {
            return new SandboxRunner(this);
        }
    }
}
