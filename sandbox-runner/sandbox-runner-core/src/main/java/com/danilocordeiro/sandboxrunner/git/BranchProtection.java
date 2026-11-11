package com.danilocordeiro.sandboxrunner.git;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * Decides whether a branch may receive a merge. The default protects the
 * usual long-lived branches; combine it with a check against your Git host
 * with {@link #or(BranchProtection)}.
 */
@FunctionalInterface
public interface BranchProtection {

    List<String> DEFAULT_PATTERNS = List.of("main", "master", "develop", "trunk", "release/*", "release-*", "hotfix/*");

    boolean isProtected(String branch);

    /** Protected if the branch matches any glob (same syntax as file globs, one segment per *). */
    static BranchProtection patterns(List<String> globs) {
        List<PathMatcher> ms = globs.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        return branch -> ms.stream().anyMatch(m -> m.matches(Path.of(branch)));
    }

    static BranchProtection defaults() {
        return patterns(DEFAULT_PATTERNS);
    }

    /** Protected if either says so. */
    default BranchProtection or(BranchProtection other) {
        return b -> isProtected(b) || other.isProtected(b);
    }
}
