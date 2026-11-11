package com.danilocordeiro.sandboxrunner.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The repository on the host. The library reads from it, fetches results into
 * it and merges in it, always through {@link Git}; it is never mounted into a
 * sandbox. Mutations of one repository are serialized by {@link #lock()}.
 */
public final class HostRepo {

    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final Path commonDir;

    private HostRepo(Path root, Path commonDir) {
        this.root = root;
        this.commonDir = commonDir;
    }

    /** Opens the repository containing dir. */
    public static HostRepo open(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            throw new IllegalArgumentException("not a directory: " + abs);
        }
        Path top = Path.of(Git.run(abs, "rev-parse", "--show-toplevel"));
        Path common = top.resolve(Git.run(top, "rev-parse", "--git-common-dir")).normalize();
        try {
            return new HostRepo(top.toRealPath(), common.toRealPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() {
        return root;
    }

    /** The shared .git directory (objects live here, also for worktrees). */
    public Path commonDir() {
        return commonDir;
    }

    public ReentrantLock lock() {
        return LOCKS.computeIfAbsent(commonDir, k -> new ReentrantLock());
    }

    public String resolve(String ref) {
        return Git.run(root, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
    }

    public boolean branchExists(String branch) {
        return Git.tryRun(root, "show-ref", "--verify", "--quiet", "refs/heads/" + branch).ok();
    }

    public String currentBranch() {
        return Git.run(root, "rev-parse", "--abbrev-ref", "HEAD");
    }

    /** The worktree where branch is checked out, if any. */
    public Optional<Path> checkedOutAt(String branch) {
        String out = Git.run(root, "worktree", "list", "--porcelain");
        Path current = null;
        for (String line : out.split("\n")) {
            if (line.startsWith("worktree ")) {
                current = Path.of(line.substring("worktree ".length()));
            } else if (line.equals("branch refs/heads/" + branch) && current != null) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    /** True when the worktree at dir has no staged, unstaged or untracked changes. */
    public static boolean isClean(Path dir) {
        return Git.run(dir, "status", "--porcelain", "--untracked-files=normal").isEmpty();
    }
}
