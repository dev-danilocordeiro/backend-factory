package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.CommitInfo;
import java.nio.file.Path;
import java.util.List;

/**
 * The agent's work, now readable on the host by the library's own git.
 *
 * @param gitDir       repository holding the result commits
 * @param ref          ref pointing at the result
 * @param gitlinks     paths added or changed as submodule entries (mode 160000)
 */
public record Imported(Path gitDir, String ref, String baseCommit, List<CommitInfo> commits, String diff,
                       List<String> changedPaths, List<String> gitlinks) {

    public Imported {
        commits = List.copyOf(commits);
        changedPaths = List.copyOf(changedPaths);
        gitlinks = List.copyOf(gitlinks);
    }

    public boolean isEmpty() {
        return commits.isEmpty();
    }

    static Imported empty(String base) {
        return new Imported(null, null, base, List.of(), "", List.of(), List.of());
    }
}
