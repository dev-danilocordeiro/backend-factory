package com.danilocordeiro.sandboxrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danilocordeiro.sandboxrunner.agent.CommandAgent;
import com.danilocordeiro.sandboxrunner.event.RunEvent;
import com.danilocordeiro.sandboxrunner.gate.Gate;
import com.danilocordeiro.sandboxrunner.git.BranchStrategy;
import com.danilocordeiro.sandboxrunner.git.Git;
import com.danilocordeiro.sandboxrunner.provider.LocalProvider;
import com.danilocordeiro.sandboxrunner.review.ReviewOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runner logic with an unisolated provider and a scripted agent; no Docker, no model. */
class SandboxRunnerTest {

    @TempDir
    Path tmp;
    Path repo;

    /** The fake agent: creates a file named after the task and commits it. */
    static final String FAKE_AGENT = "f=$(echo \"$SANDBOX_TASK\" | tr ' ' '-').txt; echo done > \"$f\"; "
            + "git add \"$f\" && git -c user.name=fake -c user.email=f@x commit -qm \"$SANDBOX_TASK\"";

    @BeforeEach
    void repo() throws IOException {
        repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        Git.run(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README"), "x\n");
        Git.run(repo, "add", "-A");
        Git.run(repo, "commit", "-q", "-m", "init");
        Git.run(repo, "branch", "feature");
    }

    SandboxRunner.Builder runner(String agentScript) {
        return SandboxRunner.builder()
                .provider(LocalProvider.unsafeForTestsOnly())
                .network(NetworkPolicy.unrestricted())
                .agent(CommandAgent.of("sh", "-c", agentScript))
                .limits(Limits.of(1, DataSize.ofMegabytes(256), Duration.ofSeconds(60)));
    }

    Task merge(String title) {
        return Task.of(title, repo, BranchStrategy.newBranchAndMerge("feature"));
    }

    List<String> sandboxBranches() {
        return Git.run(repo, "branch", "--list", "sandbox/*", "--format=%(refname:short)").lines().toList();
    }

    @Test
    void mergesAndReportsEverything() {
        List<RunEvent> events = Collections.synchronizedList(new ArrayList<>());
        RunResult r = runner(FAKE_AGENT).gate(Gate.command("ls", "ls", "add-health.txt")).listener(events::add)
                .build().run(merge("add health"));
        assertEquals(RunStatus.MERGED, r.status(), r.detail() + r.logs());
        assertEquals(Optional.of("feature"), r.branch());
        assertEquals(1, r.commits().size());
        assertTrue(r.diff().contains("+done"));
        assertEquals(1, r.gates().size());
        assertEquals("done", Git.run(repo, "show", "feature:add-health.txt"));
        assertEquals(List.of(), sandboxBranches());
        List<Class<?>> kinds = events.stream().<Class<?>>map(Object::getClass).toList();
        assertEquals(List.of(RunEvent.Started.class, RunEvent.WorkspaceReady.class, RunEvent.SandboxCreated.class,
                RunEvent.AgentStarted.class, RunEvent.AgentFinished.class, RunEvent.ChangesCollected.class,
                RunEvent.GateFinished.class, RunEvent.Delivered.class, RunEvent.CleanedUp.class, RunEvent.Finished.class), kinds);
    }

    @Test
    void redGateBlocksMergeAndCleansUp() {
        String before = Git.run(repo, "rev-parse", "feature");
        RunResult r = runner(FAKE_AGENT).gate(Gate.command("tests", "sh", "-c", "echo 2 failures; exit 1"))
                .build().run(merge("broken"));
        assertEquals(RunStatus.GATE_FAILED, r.status());
        assertTrue(r.gates().getFirst().output().contains("2 failures"));
        assertEquals(before, Git.run(repo, "rev-parse", "feature"));
        assertEquals(List.of(), sandboxBranches());
        assertFalse(r.diff().isEmpty(), "diff is still reported");
    }

    @Test
    void protectedTargetIsRefusedBeforeAnythingRuns() {
        List<RunEvent> events = new ArrayList<>();
        RunResult r = runner("touch should-not-run").listener(events::add).build()
                .run(Task.of("x", repo, BranchStrategy.newBranchAndMerge("main")));
        assertEquals(RunStatus.REFUSED_PROTECTED, r.status());
        assertFalse(events.stream().anyMatch(e -> e instanceof RunEvent.SandboxCreated));
    }

    @Test
    void noChanges() {
        assertEquals(RunStatus.NO_CHANGES, runner("true").build().run(merge("nothing")).status());
    }

    @Test
    void agentFailure() {
        RunResult r = runner("echo boom >&2; exit 7").build().run(merge("fails"));
        assertEquals(RunStatus.AGENT_FAILED, r.status());
        assertTrue(r.detail().contains("7"));
    }

    @Test
    void forbiddenPathIsAPolicyViolation() {
        RunResult r = runner("mkdir -p .github/workflows && echo evil > .github/workflows/x.yml && git add -A && "
                + "git -c user.name=f -c user.email=f@x commit -qm ci").build().run(merge("ci"));
        assertEquals(RunStatus.POLICY_VIOLATION, r.status());
        assertTrue(r.detail().contains(".github/workflows/x.yml"));
    }

    @Test
    void blockingReviewStopsTheMerge() {
        String before = Git.run(repo, "rev-parse", "feature");
        RunResult r = runner(FAKE_AGENT)
                .reviewer((diff, title) -> new ReviewOutcome("REQUEST_CHANGES", true, "SQL injection", List.of(), Optional.empty()))
                .build().run(merge("review me"));
        assertEquals(RunStatus.REVIEW_BLOCKED, r.status());
        assertEquals(before, Git.run(repo, "rev-parse", "feature"));
    }

    @Test
    void timeoutStopsTheRun() {
        RunResult r = runner("sleep 30").limits(Limits.of(1, DataSize.ofMegabytes(64), Duration.ofSeconds(2)))
                .build().run(merge("slow"));
        assertEquals(RunStatus.TIMED_OUT, r.status());
        assertTrue(r.duration().compareTo(Duration.ofSeconds(20)) < 0, "must not wait for the agent: " + r.duration());
    }

    @Test
    void parallelRunsAllMergeIntoTheSameTarget() {
        List<Task> tasks = IntStream.range(0, 6).mapToObj(i -> merge("task " + i)).toList();
        List<RunResult> rs = runner(FAKE_AGENT).build().runAll(tasks, 3);
        assertEquals(6, rs.size());
        rs.forEach(r -> assertEquals(RunStatus.MERGED, r.status(), r.detail()));
        for (int i = 0; i < 6; i++) {
            assertEquals("done", Git.run(repo, "show", "feature:task-" + i + ".txt"));
        }
        assertEquals(List.of(), sandboxBranches());
    }

    @Test
    void listenerFailureDoesNotBreakTheRun() {
        RunResult r = runner(FAKE_AGENT).listener(e -> {
            throw new IllegalStateException("listener bug");
        }).build().run(merge("robust"));
        assertEquals(RunStatus.MERGED, r.status());
        assertTrue(r.logs().stream().anyMatch(l -> l.contains("listener bug")));
    }
}
