package com.danilocordeiro.sandboxrunner.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danilocordeiro.sandboxrunner.Limits;
import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import com.danilocordeiro.sandboxrunner.RunStatus;
import com.danilocordeiro.sandboxrunner.internal.Cleanup;
import com.danilocordeiro.sandboxrunner.provider.LocalProvider;
import com.danilocordeiro.sandboxrunner.spi.ExecResult;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import com.danilocordeiro.sandboxrunner.spi.SandboxSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchStrategyTest {

    private static final Duration T = Duration.ofSeconds(60);
    private static final Map<String, String> IDENTITY = Map.of(
            "GIT_AUTHOR_NAME", "agent", "GIT_AUTHOR_EMAIL", "a@x", "GIT_COMMITTER_NAME", "agent", "GIT_COMMITTER_EMAIL", "a@x");

    @TempDir
    Path tmp;
    Path repoDir;
    HostRepo repo;
    Cleanup cleanup;
    int repos;

    @BeforeEach
    void setUp() throws IOException {
        repoDir = tmp.resolve("repo" + repos++);
        Files.createDirectories(repoDir);
        Git.run(repoDir, "init", "-q", "-b", "main");
        Files.writeString(repoDir.resolve("app.txt"), "line 1\n");
        Git.run(repoDir, "add", "-A");
        Git.run(repoDir, "commit", "-q", "-m", "init");
        Git.run(repoDir, "branch", "feature");
        repo = HostRepo.open(repoDir);
        cleanup = new Cleanup();
    }

    StrategyContext ctx(String runId, boolean retain, BranchProtection protection) throws IOException {
        Path t = Files.createDirectories(tmp.resolve("run-" + runId));
        return new StrategyContext(repo, runId, "test task", Optional.empty(), t, cleanup, protection,
                10L << 20, retain, new AtomicBoolean());
    }

    StrategyContext ctx(String runId) throws IOException {
        return ctx(runId, false, BranchProtection.defaults());
    }

    SandboxInstance sandbox(Workspace ws) {
        return LocalProvider.unsafeForTestsOnly().create(new SandboxSpec("t", "none", ws.mounts(), ws.workdir(),
                Map.of(), List.of(), NetworkPolicy.unrestricted(), Limits.defaults()));
    }

    static void sh(SandboxInstance sb, Workspace ws, String script) {
        ExecResult r = sb.exec(List.of("sh", "-c", script), ws.workdir(), IDENTITY, T);
        assertTrue(r.ok(), r.output());
    }

    String hostRefs() {
        return Git.run(repoDir, "for-each-ref", "--format=%(refname) %(objectname)");
    }

    @Test
    void newBranchAndMergeMergesAgentCommitsAndCleansUp() throws IOException {
        var s = BranchStrategy.newBranchAndMerge("feature");
        var c = ctx("r1");
        Workspace ws = s.prepare(c);
        assertTrue(ws.gitInSandbox());
        var sb = sandbox(ws);
        sh(sb, ws, "echo new > new.txt && git add new.txt && git commit -qm 'add new' && echo extra >> app.txt");

        Imported im = s.collect(ws, sb, T, c);
        assertEquals(2, im.commits().size(), "agent commit + uncommitted leftovers");
        assertEquals(List.of("app.txt", "new.txt"), im.changedPaths());
        assertTrue(im.diff().contains("+new"));

        Delivery d = s.deliver(im, c);
        assertEquals(RunStatus.MERGED, d.status());
        cleanup.releaseAll();
        assertEquals(List.of(), cleanup.failures());
        assertEquals("new", Git.run(repoDir, "show", "feature:new.txt"));
        assertEquals("init", Git.run(repoDir, "log", "-1", "--format=%s", "main"), "main untouched");
        assertFalse(repo.branchExists("sandbox/r1"), "temp branch deleted");
        String root = repoDir.toRealPath().toString();
        assertEquals("", Git.run(repoDir, "worktree", "list", "--porcelain").lines()
                .filter(l -> l.startsWith("worktree ") && !l.endsWith(root)).findAny().orElse(""));
    }

    @Test
    void mergesIntoCheckedOutTargetWhenClean() throws IOException {
        var s = BranchStrategy.newBranchAndMerge("main");
        var c = ctx("r2", false, BranchProtection.patterns(List.of()));
        Workspace ws = s.prepare(c);
        var sb = sandbox(ws);
        sh(sb, ws, "echo x > x.txt");
        Delivery d = s.deliver(s.collect(ws, sb, T, c), c);
        assertEquals(RunStatus.MERGED, d.status());
        assertTrue(Files.exists(repoDir.resolve("x.txt")), "checked-out target updated in place");
    }

    @Test
    void dirtyCheckedOutTargetIsBusy() throws IOException {
        var s = BranchStrategy.newBranchAndMerge("main");
        var c = ctx("r3", false, BranchProtection.patterns(List.of()));
        Workspace ws = s.prepare(c);
        var sb = sandbox(ws);
        sh(sb, ws, "echo x > x.txt");
        Imported im = s.collect(ws, sb, T, c);
        Files.writeString(repoDir.resolve("local-edit.txt"), "human work in progress\n");
        assertEquals(RunStatus.TARGET_BUSY, s.deliver(im, c).status());
        assertFalse(Files.exists(repoDir.resolve("x.txt")));
    }

    @Test
    void conflictIsAbortedAndTargetUnchanged() throws IOException {
        var s = BranchStrategy.newBranchAndMerge("feature");
        var c = ctx("r4");
        Workspace ws = s.prepare(c);
        var sb = sandbox(ws);
        sh(sb, ws, "echo agent > app.txt");
        Imported im = s.collect(ws, sb, T, c);
        // A human changes the same line on the target meanwhile.
        Git.run(repoDir, "checkout", "-q", "feature");
        Files.writeString(repoDir.resolve("app.txt"), "human\n");
        Git.run(repoDir, "commit", "-q", "-am", "human edit");
        Git.run(repoDir, "checkout", "-q", "main");
        String before = repo.resolve("feature");

        assertEquals(RunStatus.MERGE_CONFLICT, s.deliver(im, c).status());
        cleanup.releaseAll();
        assertEquals(before, repo.resolve("feature"));
        assertFalse(repo.branchExists("sandbox/r4"), "deleted by default");
        assertTrue(HostRepo.isClean(repoDir));
    }

    @Test
    void conflictCanRetainBranch() throws IOException {
        var s = BranchStrategy.newBranchAndMerge("feature");
        var c = ctx("r5", true, BranchProtection.defaults());
        Workspace ws = s.prepare(c);
        var sb = sandbox(ws);
        sh(sb, ws, "echo agent > app.txt");
        Imported im = s.collect(ws, sb, T, c);
        Git.run(repoDir, "checkout", "-q", "feature");
        Files.writeString(repoDir.resolve("app.txt"), "human\n");
        Git.run(repoDir, "commit", "-q", "-am", "human edit");
        Git.run(repoDir, "checkout", "-q", "main");
        Delivery d = s.deliver(im, c);
        cleanup.releaseAll();
        assertEquals(Optional.of("sandbox/r5"), d.branch());
        assertTrue(repo.branchExists("sandbox/r5"));
    }

    @Test
    void protectionIsRecheckedAtDelivery() throws IOException {
        AtomicBoolean prot = new AtomicBoolean(false);
        var s = BranchStrategy.newBranchAndMerge("feature");
        var c = ctx("r6", false, b -> prot.get());
        Workspace ws = s.prepare(c);
        var sb = sandbox(ws);
        sh(sb, ws, "echo x > x.txt");
        Imported im = s.collect(ws, sb, T, c);
        prot.set(true);
        assertEquals(RunStatus.REFUSED_PROTECTED, s.deliver(im, c).status());
        assertFalse(Git.tryRun(repoDir, "cat-file", "-e", "feature:x.txt").ok());
    }

    @Test
    void noChangesYieldsEmptyResult() throws IOException {
        var s = BranchStrategy.newBranchAndMerge("feature");
        var c = ctx("r7");
        Workspace ws = s.prepare(c);
        assertTrue(s.collect(ws, sandbox(ws), T, c).isEmpty());
    }

    @Test
    void rewrittenHistoryIsRejected() throws IOException {
        var s = BranchStrategy.newBranchAndMerge("feature");
        var c = ctx("r8");
        Workspace ws = s.prepare(c);
        var sb = sandbox(ws);
        sh(sb, ws, "git checkout -q --orphan other && git rm -rq --cached . && echo z > z.txt && git add z.txt && git commit -qm orphan");
        assertThrows(PolicyException.class, () -> s.collect(ws, sb, T, c));
    }

    @Test
    void patchOnlyLeavesHostRepositoryUntouched() throws IOException {
        String refsBefore = hostRefs();
        var s = BranchStrategy.patchOnly();
        var c = ctx("r9");
        Workspace ws = s.prepare(c);
        var sb = sandbox(ws);
        sh(sb, ws, "echo patch > p.txt && git add p.txt && git commit -qm patch");
        Imported im = s.collect(ws, sb, T, c);
        Delivery d = s.deliver(im, c);
        cleanup.releaseAll();
        assertEquals(RunStatus.PATCH_READY, d.status());
        assertTrue(im.diff().contains("+patch"));
        assertEquals(refsBefore, hostRefs());
        assertFalse(Git.tryRun(repoDir, "cat-file", "-e", im.commits().getFirst().sha()).ok(),
                "result objects must not be written into the host repository");
    }

    @Test
    void worktreeCommitsOnHostAndKeepsBranch() throws IOException {
        var s = BranchStrategy.worktree();
        var c = ctx("w1");
        Workspace ws = s.prepare(c);
        assertFalse(ws.gitInSandbox());
        var sb = sandbox(ws);
        sh(sb, ws, "echo w > w.txt");
        Imported im = s.collect(ws, sb, T, c);
        Delivery d = s.deliver(im, c);
        cleanup.releaseAll();
        assertEquals(RunStatus.BRANCH_READY, d.status());
        assertEquals("w", Git.run(repoDir, "show", "sandbox/w1:w.txt"));
        assertFalse(Files.exists(ws.hostDir()), "worktree directory removed");
    }

    @Test
    void worktreeBranchIsDeletedWhenNotDelivered() throws IOException {
        var s = BranchStrategy.worktree();
        var c = ctx("w2");
        Workspace ws = s.prepare(c);
        sh(sandbox(ws), ws, "echo w > w.txt");
        cleanup.releaseAll(); // e.g. a gate failed
        assertFalse(repo.branchExists("sandbox/w2"));
    }

    @Test
    void worktreeRejectsGitControlFiles() throws IOException {
        for (String attack : List.of(
                "echo '* filter=evil' > .gitattributes",
                "mkdir -p sub/.git && echo x > sub/.git/config",
                "echo 'gitdir: /tmp/evil' > .git")) {
            setUp();
            var s = BranchStrategy.worktree();
            var c = ctx("a" + Math.abs(attack.hashCode()));
            Workspace ws = s.prepare(c);
            var sb = sandbox(ws);
            sh(sb, ws, attack);
            assertThrows(PolicyException.class, () -> s.collect(ws, sb, T, c), attack);
            cleanup.releaseAll();
        }
    }
}
