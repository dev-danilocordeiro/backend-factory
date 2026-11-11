package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.RunStatus;
import com.danilocordeiro.sandboxrunner.spi.Mount;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A git worktree of the host repository on branch sandbox/&lt;run&gt;. The sandbox
 * gets the files only: the worktree's .git pointer is mounted read-only and
 * the host's git directory is not mounted at all, so the agent cannot commit
 * and cannot reach git metadata. After the agent, the library verifies that
 * no git-controlling file changed and commits the result on the host.
 */
final class WorktreeStrategy implements BranchStrategy {

    private static final List<String> GIT_CONTROL_FILES = List.of(".gitattributes", ".gitmodules");
    /** .git pointer content per run; one strategy instance may serve parallel runs. */
    private final Map<String, byte[]> gitPointers = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "worktree";
    }

    @Override
    public Optional<String> target() {
        return Optional.empty();
    }

    @Override
    public Workspace prepare(StrategyContext ctx) {
        HostRepo repo = ctx.repo();
        String base = repo.resolve(ctx.base().orElse("HEAD"));
        Path wt = ctx.tempDir().resolve("worktree");
        // Registered before the worktree so that, in reverse order, the worktree goes first.
        ctx.cleanup().push("delete branch " + ctx.branch(), () -> {
            if (!ctx.keepBranch().get()) {
                Git.tryRun(repo.root(), "branch", "-D", ctx.branch());
            }
        });
        Git.run(repo.root(), "worktree", "add", "-q", "-b", ctx.branch(), wt.toString(), base);
        ctx.cleanup().push("remove worktree", () -> {
            Git.tryRun(repo.root(), "worktree", "remove", "--force", wt.toString());
            Git.tryRun(repo.root(), "worktree", "prune");
        });
        try {
            gitPointers.put(ctx.runId(), Files.readAllBytes(wt.resolve(".git")));
            ctx.cleanup().push("forget .git pointer", () -> gitPointers.remove(ctx.runId()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Workspace(List.of(new Mount(wt, Workspace.WORKDIR, false),
                new Mount(wt.resolve(".git"), Workspace.WORKDIR + "/.git", true)),
                Workspace.WORKDIR, false, base, ctx.branch(), wt);
    }

    @Override
    public Imported collect(Workspace ws, SandboxInstance sandbox, Duration timeout, StrategyContext ctx) {
        verifyIntegrity(ws, ctx);
        Path wt = ws.hostDir();
        Git.run(wt, "add", "-A");
        if (!Git.tryRun(wt, "diff", "--cached", "--quiet").ok()) {
            Git.run(wt, "commit", "-q", "-m", "sandbox: " + ctx.title());
        }
        return Results.read(ctx.repo().root(), ws.baseCommit(), "refs/heads/" + ws.branch());
    }

    @Override
    public Delivery deliver(Imported result, StrategyContext ctx) {
        ctx.keepBranch().set(true);
        return new Delivery(RunStatus.BRANCH_READY, Optional.of(ctx.branch()),
                result.commits().size() + " commit(s) on " + ctx.branch());
    }

    /** No .git pointer change, no nested repositories, no new or changed .gitattributes/.gitmodules. */
    private void verifyIntegrity(Workspace ws, StrategyContext ctx) {
        Path wt = ws.hostDir();
        try {
            Path pointer = wt.resolve(".git");
            if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(Files.readAllBytes(pointer), gitPointers.get(ctx.runId()))) {
                throw new PolicyException("the worktree's .git pointer was modified");
            }
            Map<String, String> tracked = new HashMap<>();
            String ls = Git.run(ctx.repo().root(), "ls-tree", "-r", "-z", "--full-tree", ws.baseCommit());
            for (String entry : ls.split("\u0000")) {
                String[] f = entry.split("\t", 2);
                if (f.length == 2 && GIT_CONTROL_FILES.contains(Path.of(f[1]).getFileName().toString())) {
                    tracked.put(f[1], f[0].split(" ")[2]);
                }
            }
            Files.walkFileTree(wt, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(wt) && dir.getFileName().toString().equals(".git")) {
                        throw new PolicyException("nested git directory: " + wt.relativize(dir));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = wt.relativize(file).toString();
                    if (GIT_CONTROL_FILES.contains(file.getFileName().toString())) {
                        String want = tracked.get(rel);
                        if (want == null || !want.equals(blobId(file))) {
                            throw new PolicyException("git control file added or changed: " + rel);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The git blob id of a file, computed without running git on it. */
    static String blobId(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(("blob " + content.length + "\u0000").getBytes(StandardCharsets.UTF_8));
            sha.update(content);
            return HexFormat.of().formatHex(sha.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
