package com.danilocordeiro.sandboxrunner;

import com.danilocordeiro.sandboxrunner.git.BranchStrategy;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One unit of work for an agent.
 *
 * @param title    short description; used in commit and merge messages
 * @param prompt   what the agent is told; defaults to the title
 * @param repo     any directory inside the host repository
 * @param base     commit-ish to start from; empty means the strategy default
 *                 (the target branch for merges, HEAD otherwise)
 */
public record Task(String title, String prompt, Path repo, BranchStrategy strategy, Optional<String> base) {

    public Task {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(base, "base");
        if (title.isBlank() || prompt.isBlank()) {
            throw new IllegalArgumentException("title and prompt must not be blank");
        }
    }

    public static Task of(String title, Path repo, BranchStrategy strategy) {
        return new Task(title, title, repo, strategy, Optional.empty());
    }

    public Task withPrompt(String p) {
        return new Task(title, p, repo, strategy, base);
    }

    public Task withBase(String b) {
        return new Task(title, prompt, repo, strategy, Optional.of(b));
    }
}
