package com.danilocordeiro.sandboxrunner;

/** Final outcome of a run. Only the first three are successes. */
public enum RunStatus {
    /** Commits merged into the target branch (newBranchAndMerge). */
    MERGED,
    /** Commits left on a sandbox/* branch for a human (worktree). */
    BRANCH_READY,
    /** Diff returned; host repository untouched (patchOnly). */
    PATCH_READY,
    /** The agent finished without changing anything. */
    NO_CHANGES,
    /** A gate failed; nothing was merged. */
    GATE_FAILED,
    /** The reviewer requested changes or could not review; nothing was merged. */
    REVIEW_BLOCKED,
    /** The merge conflicted and was aborted; the target branch is unchanged. */
    MERGE_CONFLICT,
    /** The target branch is protected. Nothing was run. */
    REFUSED_PROTECTED,
    /** The target branch is checked out with local changes. */
    TARGET_BUSY,
    /** The change touches forbidden paths, is too large, or contains a secret. */
    POLICY_VIOLATION,
    /** The agent process failed. */
    AGENT_FAILED,
    /** The run exceeded its time limit; the sandbox was destroyed. */
    TIMED_OUT,
    /** The sandbox stopped unexpectedly. */
    SANDBOX_DIED,
    /** Anything else (bad configuration, git or provider error). */
    ERROR;

    public boolean isSuccess() {
        return this == MERGED || this == BRANCH_READY || this == PATCH_READY;
    }
}
