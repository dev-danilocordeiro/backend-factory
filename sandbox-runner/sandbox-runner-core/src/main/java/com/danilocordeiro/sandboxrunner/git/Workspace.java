package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.spi.Mount;
import java.nio.file.Path;
import java.util.List;

/**
 * Where the agent works.
 *
 * @param mounts         what the sandbox sees; never the host repository
 * @param workdir        path inside the sandbox where the agent starts
 * @param gitInSandbox   whether the agent has a git repository (and can commit)
 * @param baseCommit     full SHA the work starts from
 * @param hostDir        host directory of the work (clone or worktree)
 */
public record Workspace(List<Mount> mounts, String workdir, boolean gitInSandbox, String baseCommit,
                        String branch, Path hostDir) {

    public static final String WORKDIR = "/workspace";
    public static final String OUTDIR = "/out";

    public Workspace {
        mounts = List.copyOf(mounts);
    }
}
