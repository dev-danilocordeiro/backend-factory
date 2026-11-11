package com.danilocordeiro.sandboxrunner;

import com.danilocordeiro.sandboxrunner.git.Imported;
import com.danilocordeiro.sandboxrunner.git.PolicyException;
import com.danilocordeiro.sandboxrunner.internal.Redactor;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Rules every result must pass before gates run. By default the agent may not
 * change files that control git or CI (they would act on the host or the
 * pipeline after merge), may not add submodules, and its diff may not contain
 * any credential given to the sandbox.
 */
public record ChangePolicy(List<String> deniedGlobs, boolean allowSubmodules) {

    public static final List<String> DEFAULT_DENIED = List.of(
            ".gitattributes", "**/.gitattributes", ".gitmodules", "**/.gitmodules",
            ".github/**", ".gitlab-ci.yml", ".husky/**");

    public ChangePolicy {
        deniedGlobs = List.copyOf(deniedGlobs);
    }

    public static ChangePolicy defaults() {
        return new ChangePolicy(DEFAULT_DENIED, false);
    }

    public ChangePolicy deny(String... globs) {
        List<String> all = new ArrayList<>(deniedGlobs);
        all.addAll(List.of(globs));
        return new ChangePolicy(all, allowSubmodules);
    }

    /** Throws {@link PolicyException} on the first violation. */
    void check(Imported result, Redactor redactor, DataSize maxDiff) {
        List<PathMatcher> ms = deniedGlobs.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        for (String p : result.changedPaths()) {
            if (ms.stream().anyMatch(m -> m.matches(Path.of(p)))) {
                throw new PolicyException("forbidden path changed: " + p);
            }
        }
        if (!allowSubmodules && !result.gitlinks().isEmpty()) {
            throw new PolicyException("submodule entries are not allowed: " + result.gitlinks());
        }
        long size = result.diff().getBytes(StandardCharsets.UTF_8).length;
        if (size > maxDiff.bytes()) {
            throw new PolicyException("diff is " + size + " bytes, limit is " + maxDiff.bytes());
        }
        if (redactor.leaks(result.diff())) {
            throw new PolicyException("the diff contains a credential given to the sandbox");
        }
    }
}
