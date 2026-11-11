package com.danilocordeiro.sandboxrunner;

import com.danilocordeiro.sandboxrunner.agent.ClaudeCode;
import com.danilocordeiro.sandboxrunner.provider.DockerProvider;

/**
 * One-call entry point with opinionated defaults: Docker, the reference agent
 * image ({@code docker/agent-jdk21}, built locally as {@value #DEFAULT_IMAGE}),
 * Claude Code headless, network limited to api.anthropic.com, default limits.
 * For anything else, use {@link SandboxRunner#builder()}.
 */
public final class Sandbox {

    public static final String DEFAULT_IMAGE = "sandbox-runner/agent-jdk21:0.1";

    private Sandbox() {
    }

    /** Runs task with the defaults. Reads ANTHROPIC_API_KEY, or else CLAUDE_CODE_OAUTH_TOKEN, from the environment. */
    public static RunResult run(Task task) {
        return defaults().build().run(task);
    }

    /** The builder behind {@link #run(Task)}, to adjust one or two settings. */
    public static SandboxRunner.Builder defaults() {
        String var = System.getenv("ANTHROPIC_API_KEY") != null ? "ANTHROPIC_API_KEY" : "CLAUDE_CODE_OAUTH_TOKEN";
        return SandboxRunner.builder()
                .provider(DockerProvider.image(DEFAULT_IMAGE))
                .agent(ClaudeCode.headless())
                .credential(Secret.fromEnv(var))
                .network(NetworkPolicy.allow("api.anthropic.com"))
                .env("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
    }
}
