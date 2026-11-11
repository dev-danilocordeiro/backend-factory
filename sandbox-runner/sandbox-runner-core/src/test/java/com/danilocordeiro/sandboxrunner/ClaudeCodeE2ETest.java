package com.danilocordeiro.sandboxrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real Claude Code in the reference image. Costs model usage, so it only
 * runs with SANDBOX_RUNNER_E2E=1 and a credential:
 * SANDBOX_RUNNER_E2E=1 ANTHROPIC_API_KEY=... mvn test -Pe2e
 */
@Tag("e2e")
class ClaudeCodeE2ETest {

    @TempDir
    Path tmp;

    @Test
    void claudeCodeCommitsInsideTheSandboxAndTheResultIsMerged() throws IOException {
        assumeTrue("1".equals(System.getenv("SANDBOX_RUNNER_E2E")), "set SANDBOX_RUNNER_E2E=1");
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null || System.getenv("CLAUDE_CODE_OAUTH_TOKEN") != null,
                "needs ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN");
        Proc.Result build = Proc.run(List.of("docker", "build", "-q", "-t", Sandbox.DEFAULT_IMAGE,
                Path.of("../docker/agent-jdk21").toAbsolutePath().toString()), null, Map.of(), Duration.ofMinutes(20));
        assertTrue(build.ok(), build.output());

        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        Git.run(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "# demo\n");
        Git.run(repo, "add", "-A");
        Git.run(repo, "commit", "-q", "-m", "init");
        Git.run(repo, "branch", "work");

        RunResult r = Sandbox.defaults()
                .agent(com.danilocordeiro.sandboxrunner.agent.ClaudeCode.headless().maxTurns(10).maxBudgetUsd(1))
                .limits(Limits.of(2, DataSize.ofGigabytes(2), Duration.ofMinutes(10)))
                .gate(Gate.command("file", "grep", "-qi", "hello", "HELLO.md"))
                .build()
                .run(Task.of("Create HELLO.md", repo, BranchStrategy.newBranchAndMerge("work"))
                        .withPrompt("Create a file named HELLO.md containing the single word hello, then commit it with git."));
        assertEquals(RunStatus.MERGED, r.status(), r.detail() + "\n" + String.join("\n", r.logs()));
        assertTrue(Git.run(repo, "show", "work:HELLO.md").toLowerCase().contains("hello"));
    }
}
