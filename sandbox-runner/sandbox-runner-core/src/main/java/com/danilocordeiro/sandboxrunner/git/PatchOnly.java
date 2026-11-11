package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.RunStatus;
import java.util.Optional;

/** Private clone; returns the diff and leaves the host repository untouched. */
final class PatchOnly extends CloneBased {

    @Override
    public String name() {
        return "patch-only";
    }

    @Override
    public Optional<String> target() {
        return Optional.empty();
    }

    @Override
    String baseRef(StrategyContext ctx) {
        return ctx.base().orElse("HEAD");
    }

    @Override
    public Delivery deliver(Imported result, StrategyContext ctx) {
        return new Delivery(RunStatus.PATCH_READY, Optional.empty(), result.commits().size() + " commit(s) as a patch");
    }
}
