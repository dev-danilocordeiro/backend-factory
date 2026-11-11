package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.spi.ExecResult;
import com.danilocordeiro.sandboxrunner.spi.Mount;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The agent works in a private clone with its own .git, so it can commit.
 * The host never runs git inside that clone after the agent started: the
 * sandbox writes a git bundle, and the host fetches it into a scratch
 * repository it created itself (borrowing the host's objects read-only).
 */
abstract class CloneBased implements BranchStrategy {

    static final String BUNDLE = "result.bundle";
    static final String RESULT_REF = "refs/heads/result";

    /** Runs inside the sandbox: commit leftovers, then bundle everything after the base. */
    static final String COLLECT_SCRIPT = """
            set -e
            git add -A
            if ! git diff --cached --quiet; then git commit -q -m "sandbox: uncommitted changes"; fi
            if [ "$(git rev-parse HEAD)" = "$SR_BASE" ]; then echo SR_NO_CHANGES; exit 0; fi
            git bundle create "$SR_OUT/result.bundle" HEAD "^$SR_BASE" 2>/dev/null
            echo SR_BUNDLED
            """;

    abstract String baseRef(StrategyContext ctx);

    @Override
    public Workspace prepare(StrategyContext ctx) {
        String base = ctx.repo().resolve(baseRef(ctx));
        Path clone = ctx.tempDir().resolve("clone");
        Path out = ctx.tempDir().resolve("out");
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Git.run(ctx.tempDir(), "clone", "-q", "--no-hardlinks", "--no-checkout", ctx.repo().root().toString(), clone.toString());
        Git.run(clone, "checkout", "-q", "-b", ctx.branch(), base);
        // No remote: nothing inside the sandbox can push back to where this came from.
        Git.run(clone, "remote", "remove", "origin");
        return new Workspace(List.of(new Mount(clone, Workspace.WORKDIR, false), new Mount(out, Workspace.OUTDIR, false)),
                Workspace.WORKDIR, true, base, ctx.branch(), clone);
    }

    @Override
    public Imported collect(Workspace ws, SandboxInstance sandbox, Duration timeout, StrategyContext ctx) {
        ExecResult r = sandbox.exec(List.of("sh", "-c", COLLECT_SCRIPT), ws.workdir(),
                Map.of("SR_BASE", ws.baseCommit(), "SR_OUT", Workspace.OUTDIR,
                        "GIT_AUTHOR_NAME", "sandbox-agent", "GIT_AUTHOR_EMAIL", "sandbox-agent@localhost",
                        "GIT_COMMITTER_NAME", "sandbox-agent", "GIT_COMMITTER_EMAIL", "sandbox-agent@localhost"),
                timeout);
        if (!r.ok()) {
            throw new Git.GitException("collecting the result in the sandbox failed: " + r.output().strip());
        }
        if (r.stdout().contains("SR_NO_CHANGES")) {
            return Imported.empty(ws.baseCommit());
        }
        Path bundle = ctx.tempDir().resolve("out").resolve(BUNDLE);
        long size;
        try {
            if (!Files.isRegularFile(bundle, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new PolicyException("the sandbox did not produce a regular bundle file");
            }
            size = Files.size(bundle);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (size > ctx.maxBundleBytes()) {
            throw new PolicyException("result bundle is " + size + " bytes, limit is " + ctx.maxBundleBytes());
        }
        Path scratch = ctx.tempDir().resolve("scratch.git");
        Git.run(ctx.tempDir(), "init", "-q", "--bare", scratch.toString());
        try {
            Files.writeString(scratch.resolve("objects/info/alternates"),
                    ctx.repo().commonDir().resolve("objects") + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Git.run(scratch, "fetch", "-q", "--no-tags", bundle.toString(), "HEAD:" + RESULT_REF);
        return Results.read(scratch, ws.baseCommit(), RESULT_REF);
    }
}
