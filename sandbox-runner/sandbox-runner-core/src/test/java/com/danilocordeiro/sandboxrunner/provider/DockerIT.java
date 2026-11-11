package com.danilocordeiro.sandboxrunner.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danilocordeiro.sandboxrunner.DataSize;
import com.danilocordeiro.sandboxrunner.Limits;
import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import com.danilocordeiro.sandboxrunner.RunResult;
import com.danilocordeiro.sandboxrunner.RunStatus;
import com.danilocordeiro.sandboxrunner.SandboxRunner;
import com.danilocordeiro.sandboxrunner.Secret;
import com.danilocordeiro.sandboxrunner.Task;
import com.danilocordeiro.sandboxrunner.agent.CommandAgent;
import com.danilocordeiro.sandboxrunner.event.RunEvent;
import com.danilocordeiro.sandboxrunner.gate.Gate;
import com.danilocordeiro.sandboxrunner.git.BranchStrategy;
import com.danilocordeiro.sandboxrunner.git.Git;
import com.danilocordeiro.sandboxrunner.internal.Proc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real containers, fake agent (a shell script). Run with: mvn verify -Pdocker */
@Tag("docker")
class DockerIT {

    static final String IMAGE = "sandbox-runner-test-agent:0.1";
    static final String COMMIT = "git add -A && git -c user.name=fake -c user.email=f@x commit -qm \"$SANDBOX_TASK\"";

    @TempDir
    Path tmp;
    Path repo;

    @BeforeAll
    static void buildImage() throws IOException {
        Path ctx = Path.of("src/test/resources/test-agent").toAbsolutePath();
        Proc.Result r = Proc.run(List.of("docker", "build", "-q", "-t", IMAGE, ctx.toString()), null, Map.of(), Duration.ofMinutes(10));
        assertTrue(r.ok(), r.output());
    }

    @BeforeEach
    void repo() throws IOException {
        repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        Git.run(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("app.txt"), "v1\n");
        Git.run(repo, "add", "-A");
        Git.run(repo, "commit", "-q", "-m", "init");
        Git.run(repo, "branch", "feature");
    }

    @AfterEach
    void nothingLeftBehind() {
        String containers = Proc.run(List.of("docker", "ps", "-a", "-q", "--filter", "label=sandbox-runner.managed=true"),
                null, Map.of(), Duration.ofSeconds(30)).stdout().strip();
        String networks = Proc.run(List.of("docker", "network", "ls", "-q", "--filter", "label=sandbox-runner.managed=true"),
                null, Map.of(), Duration.ofSeconds(30)).stdout().strip();
        assertEquals("", containers, "containers left behind");
        assertEquals("", networks, "networks left behind");
        assertEquals("", Git.run(repo, "branch", "--list", "sandbox/*"), "temporary branches left behind");
    }

    SandboxRunner.Builder runner(String script) {
        return SandboxRunner.builder()
                .provider(DockerProvider.image(IMAGE))
                .agent(CommandAgent.of("sh", "-c", script))
                .limits(Limits.of(1, DataSize.ofMegabytes(256), Duration.ofSeconds(90)));
    }

    Task merge(String title) {
        return Task.of(title, repo, BranchStrategy.newBranchAndMerge("feature"));
    }

    @Test
    void fakeAgentCommitIsMergedAndHostRepoIsNeverMounted() {
        AtomicReference<String> mounts = new AtomicReference<>();
        RunResult r = runner("echo hello > hello.txt && " + COMMIT)
                .gate(Gate.command("file-exists", "test", "-f", "hello.txt"))
                .listener(e -> {
                    if (e instanceof RunEvent.SandboxCreated) {
                        mounts.set(Proc.run(List.of("docker", "inspect", "-f", "{{range .Mounts}}{{.Source}}:{{.RW}} {{end}}",
                                "sandbox-runner-" + e.runId()), null, Map.of(), Duration.ofSeconds(30)).stdout());
                    }
                })
                .build().run(merge("say hello"));
        assertEquals(RunStatus.MERGED, r.status(), r.detail() + r.logs());
        assertEquals("hello", Git.run(repo, "show", "feature:hello.txt"));
        String repoReal = repo.toAbsolutePath().toString();
        for (String m : mounts.get().strip().split(" ")) {
            assertFalse(m.startsWith(repoReal), "host repository must not be mounted: " + m);
        }
    }

    @Test
    void worktreeStrategyHasNoGitInsideAndProtectsThePointer() {
        RunResult r = runner("echo w > w.txt; git status >/dev/null 2>&1 && echo HAS_GIT; echo x > .git && echo WROTE_POINTER; true")
                .build().run(Task.of("worktree", repo, BranchStrategy.worktree()));
        assertEquals(RunStatus.BRANCH_READY, r.status(), r.detail() + r.logs());
        String log = String.join("\n", r.logs());
        assertFalse(log.contains("HAS_GIT"), log);
        assertFalse(log.contains("WROTE_POINTER"), log);
        assertEquals("w", Git.run(repo, "show", r.branch().orElseThrow() + ":w.txt"));
        Git.run(repo, "branch", "-D", r.branch().orElseThrow()); // delivered result; the test cleans it
    }

    @Test
    void patchOnlyReturnsDiff() {
        RunResult r = runner("echo p >> app.txt && " + COMMIT).build().run(Task.of("patch", repo, BranchStrategy.patchOnly()));
        assertEquals(RunStatus.PATCH_READY, r.status(), r.detail());
        assertTrue(r.diff().contains("+p"));
    }

    @Test
    void timeoutDestroysTheContainer() {
        RunResult r = runner("sleep 600").limits(Limits.of(1, DataSize.ofMegabytes(128), Duration.ofSeconds(8)))
                .build().run(merge("slow"));
        assertEquals(RunStatus.TIMED_OUT, r.status(), r.detail());
        assertTrue(r.duration().toSeconds() < 60, r.duration().toString());
    }

    @Test
    void redGateBlocksMerge() {
        String before = Git.run(repo, "rev-parse", "feature");
        RunResult r = runner("echo bad > bad.txt && " + COMMIT)
                .gate(Gate.command("tests", "sh", "-c", "echo 'FAIL: 1 test'; exit 1")).build().run(merge("bad"));
        assertEquals(RunStatus.GATE_FAILED, r.status());
        assertTrue(r.gates().getFirst().output().contains("FAIL: 1 test"));
        assertEquals(before, Git.run(repo, "rev-parse", "feature"));
    }

    @Test
    void mergeConflictIsAbortedAndReported() {
        RunResult r = runner("sleep 2; echo agent > app.txt && " + COMMIT)
                .listener(e -> {
                    if (e instanceof RunEvent.AgentStarted) {
                        // A human commits to the target while the agent works.
                        Git.run(repo, "checkout", "-q", "feature");
                        try {
                            Files.writeString(repo.resolve("app.txt"), "human\n");
                        } catch (IOException ex) {
                            throw new IllegalStateException(ex);
                        }
                        Git.run(repo, "commit", "-q", "-am", "human");
                        Git.run(repo, "checkout", "-q", "main");
                    }
                }).build().run(merge("conflict"));
        assertEquals(RunStatus.MERGE_CONFLICT, r.status(), r.detail());
        assertEquals("human", Git.run(repo, "show", "feature:app.txt"));
        assertTrue(r.diff().contains("+agent"), "the work is still in the result");
    }

    @Test
    void containerKilledMidRunIsReported() {
        RunResult r = runner("sleep 60").listener(e -> {
            if (e instanceof RunEvent.AgentStarted) {
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    Proc.run(List.of("docker", "kill", "sandbox-runner-" + e.runId()), null, Map.of(), Duration.ofSeconds(30));
                });
            }
        }).build().run(merge("dies"));
        assertEquals(RunStatus.SANDBOX_DIED, r.status(), r.detail());
    }

    @Test
    void secretsAreRedactedAndCannotBeCommitted() {
        Secret key = Secret.of("API_TOKEN", "tok-" + "9f8e7d6c5b4a39281706");
        RunResult ok = runner("echo \"using $API_TOKEN\"; echo fine > f.txt && " + COMMIT).credential(key)
                .build().run(merge("uses token"));
        assertEquals(RunStatus.MERGED, ok.status(), ok.detail());
        assertTrue(String.join("\n", ok.logs()).contains("using ****"), String.join("\n", ok.logs()));
        assertFalse(ok.toString().contains(key.reveal()));

        RunResult leak = runner("echo \"$API_TOKEN\" > leaked.txt && " + COMMIT).credential(key).build().run(merge("leaks token"));
        assertEquals(RunStatus.POLICY_VIOLATION, leak.status());
        assertFalse(leak.toString().contains(key.reveal()));
    }

    @Test
    void networkIsOffByDefault() {
        RunResult r = runner("if curl -sS -m 5 -o /dev/null https://example.com; then echo yes; else echo no; fi > net.txt && " + COMMIT)
                .build().run(merge("net none"));
        assertEquals(RunStatus.MERGED, r.status(), r.detail());
        assertEquals("no", Git.run(repo, "show", "feature:net.txt"));
    }

    @Test
    void allowlistOpensOnlyListedDomains() {
        String probe = "for h in example.com example.org; do if curl -sS -m 15 -o /dev/null https://$h; then echo $h=yes; "
                + "else echo $h=no; fi; done > net.txt; "
                + "if curl -sS -m 5 --noproxy '*' -o /dev/null https://example.com; then echo direct=yes; else echo direct=no; fi >> net.txt && "
                + COMMIT;
        RunResult r = runner(probe).network(NetworkPolicy.allow("example.com")).build().run(merge("allowlist"));
        assertEquals(RunStatus.MERGED, r.status(), r.detail() + r.logs());
        assertEquals("example.com=yes\nexample.org=no\ndirect=no", Git.run(repo, "show", "feature:net.txt"));
    }
}
