package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.internal.Proc;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Host-side git, hardened. Every call disables repository hooks and
 * fsmonitor, and forbids every transport except local files, so no git
 * command run by the library can execute repository-supplied programs or
 * reach the network. Git is never run inside a directory the agent could
 * write to, except the worktree strategy's worktree after its integrity check.
 */
public final class Git {

    static final Duration TIMEOUT = Duration.ofMinutes(5);
    static final String IDENTITY_NAME = "sandbox-runner";
    static final String IDENTITY_EMAIL = "sandbox-runner@localhost";

    private Git() {
    }

    static List<String> command(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git",
                "-c", "core.hooksPath=/dev/null",
                "-c", "core.fsmonitor=false",
                "-c", "protocol.allow=never",
                "-c", "protocol.file.allow=always",
                "-c", "user.name=" + IDENTITY_NAME,
                "-c", "user.email=" + IDENTITY_EMAIL,
                "-c", "commit.gpgSign=false",
                "-C", dir.toString()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    static Map<String, String> env() {
        return Map.of(
                "GIT_TERMINAL_PROMPT", "0",
                "GIT_ASKPASS", "/bin/false",
                "GIT_EDITOR", "true",
                "GIT_MERGE_AUTOEDIT", "no",
                "LC_ALL", "C");
    }

    /** Runs git and returns the result without failing. */
    public static Proc.Result tryRun(Path dir, String... args) {
        return Proc.run(command(dir, args), null, env(), TIMEOUT);
    }

    /** Runs git and returns trimmed stdout, or throws with git's message. */
    public static String run(Path dir, String... args) {
        Proc.Result r = tryRun(dir, args);
        if (!r.ok()) {
            throw new GitException("git " + String.join(" ", args) + " failed: " + r.output().strip());
        }
        return r.stdout().strip();
    }

    /** Git failed. */
    public static final class GitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public GitException(String message) {
            super(message);
        }
    }
}
