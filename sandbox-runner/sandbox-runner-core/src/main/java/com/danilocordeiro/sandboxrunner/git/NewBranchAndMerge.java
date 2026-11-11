package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.RunStatus;
import com.danilocordeiro.sandboxrunner.internal.Proc;
import java.nio.file.Path;
import java.util.Optional;

/** Private clone on sandbox/&lt;run&gt;; on success the commits are merged into the target branch. */
final class NewBranchAndMerge extends CloneBased {

    private final String target;

    NewBranchAndMerge(String target) {
        if (target == null || target.isBlank() || target.startsWith("sandbox/")) {
            throw new IllegalArgumentException("invalid target branch: " + target);
        }
        this.target = target;
    }

    @Override
    public String name() {
        return "new-branch-and-merge";
    }

    @Override
    public Optional<String> target() {
        return Optional.of(target);
    }

    @Override
    String baseRef(StrategyContext ctx) {
        if (!ctx.repo().branchExists(target)) {
            throw new IllegalArgumentException("target branch does not exist: " + target);
        }
        return ctx.base().orElse("refs/heads/" + target);
    }

    @Override
    public Delivery deliver(Imported result, StrategyContext ctx) {
        HostRepo repo = ctx.repo();
        // Checked again here: protection can change while the agent runs.
        if (ctx.protection().isProtected(target)) {
            return new Delivery(RunStatus.REFUSED_PROTECTED, Optional.empty(), "target " + target + " is protected");
        }
        String temp = ctx.branch();
        ctx.cleanup().push("delete branch " + temp, () -> {
            if (!ctx.keepBranch().get()) {
                Git.tryRun(repo.root(), "branch", "-D", temp);
            }
        });
        Git.run(repo.root(), "fetch", "-q", "--no-tags", result.gitDir().toString(), result.ref() + ":refs/heads/" + temp);

        Optional<Path> checkedOut = repo.checkedOutAt(target);
        Path mergeDir;
        if (checkedOut.isPresent()) {
            if (!HostRepo.isClean(checkedOut.get())) {
                return new Delivery(RunStatus.TARGET_BUSY, Optional.of(temp),
                        target + " is checked out at " + checkedOut.get() + " with local changes");
            }
            mergeDir = checkedOut.get();
        } else {
            mergeDir = ctx.tempDir().resolve("merge");
            Git.run(repo.root(), "worktree", "add", "-q", mergeDir.toString(), target);
            ctx.cleanup().push("remove merge worktree", () -> Git.tryRun(repo.root(), "worktree", "remove", "--force", mergeDir.toString()));
        }
        Proc.Result merge = Git.tryRun(mergeDir, "merge", "--no-ff", "--no-edit",
                "-m", "Merge " + temp + ": " + ctx.title(), temp);
        if (!merge.ok()) {
            Git.tryRun(mergeDir, "merge", "--abort");
            if (ctx.retainOnConflict()) {
                ctx.keepBranch().set(true);
            }
            return new Delivery(RunStatus.MERGE_CONFLICT, ctx.retainOnConflict() ? Optional.of(temp) : Optional.empty(),
                    "merge into " + target + " failed and was aborted: " + merge.output().strip());
        }
        return new Delivery(RunStatus.MERGED, Optional.of(target), "merged " + result.commits().size() + " commit(s) into " + target);
    }
}
