package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.internal.Cleanup;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a strategy gets for one run.
 *
 * @param base       commit-ish to start from; empty means the strategy's default
 * @param tempDir    private directory, deleted at cleanup
 * @param keepBranch set by the strategy when the sandbox/* branch must survive cleanup
 */
public record StrategyContext(HostRepo repo, String runId, String title, Optional<String> base, Path tempDir,
                              Cleanup cleanup, BranchProtection protection, long maxBundleBytes,
                              boolean retainOnConflict, AtomicBoolean keepBranch) {

    /** The temporary branch name for this run. */
    public String branch() {
        return "sandbox/" + runId;
    }
}
