package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import java.time.Duration;
import java.util.Optional;

/**
 * Decides where the agent works and how its result comes back.
 *
 * <ul>
 *   <li>{@link #newBranchAndMerge(String)}: private clone; commits are merged into the target branch.</li>
 *   <li>{@link #worktree()}: a git worktree on the host; the sandbox sees its files but no git metadata,
 *       and the library commits the result to a sandbox/* branch left for a human.</li>
 *   <li>{@link #patchOnly()}: private clone; only the diff is returned, the host repository is untouched.</li>
 * </ul>
 */
public interface BranchStrategy {

    String name();

    /** The branch the result will be merged into, if the strategy merges. */
    Optional<String> target();

    /** Creates the workspace. Runs before the sandbox exists. */
    Workspace prepare(StrategyContext ctx);

    /** Brings the agent's work to the host, once the agent has finished. */
    Imported collect(Workspace ws, SandboxInstance sandbox, Duration timeout, StrategyContext ctx);

    /** Delivers an approved result. Only called after every gate and the reviewer passed. */
    Delivery deliver(Imported result, StrategyContext ctx);

    static BranchStrategy newBranchAndMerge(String targetBranch) {
        return new NewBranchAndMerge(targetBranch);
    }

    static BranchStrategy worktree() {
        return new WorktreeStrategy();
    }

    static BranchStrategy patchOnly() {
        return new PatchOnly();
    }
}
