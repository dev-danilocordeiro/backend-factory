package com.danilocordeiro.sandboxrunner.spi;

import com.danilocordeiro.sandboxrunner.Limits;
import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import com.danilocordeiro.sandboxrunner.Secret;
import java.util.List;
import java.util.Map;

/**
 * What a provider must create. The image (or equivalent) belongs to the provider.
 * Mounts never include the host repository: only
 * a private clone or a worktree directory prepared by the branch strategy.
 *
 * @param runId   unique id, used for names and labels so orphans can be reaped
 * @param env     plain, non-secret environment
 * @param secrets injected as environment variables, never on a command line
 */
public record SandboxSpec(String runId, List<Mount> mounts, String workdir,
                          Map<String, String> env, List<Secret> secrets, NetworkPolicy network, Limits limits) {

    public SandboxSpec {
        mounts = List.copyOf(mounts);
        env = Map.copyOf(env);
        secrets = List.copyOf(secrets);
    }
}
