package com.danilocordeiro.sandboxrunner.provider;

import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import com.danilocordeiro.sandboxrunner.internal.Proc;
import com.danilocordeiro.sandboxrunner.spi.ExecResult;
import com.danilocordeiro.sandboxrunner.spi.Mount;
import com.danilocordeiro.sandboxrunner.spi.SandboxDiedException;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import com.danilocordeiro.sandboxrunner.spi.SandboxProvider;
import com.danilocordeiro.sandboxrunner.spi.SandboxSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Runs commands directly on the host. <b>No isolation at all</b>: it exists so
 * the library's own logic can be tested without Docker. It refuses secrets and
 * any network policy other than {@link NetworkPolicy#unrestricted()}, so it
 * cannot be mistaken for a sandbox. Sandbox paths are rewritten to the mounted
 * host paths in the working directory, arguments and environment values.
 */
public final class LocalProvider implements SandboxProvider {

    private static final Logger LOG = Logger.getLogger(LocalProvider.class.getName());

    private LocalProvider() {
    }

    public static LocalProvider unsafeForTestsOnly() {
        return new LocalProvider();
    }

    @Override
    public String name() {
        return "local-unsafe";
    }

    @Override
    public SandboxInstance create(SandboxSpec spec) {
        if (!spec.secrets().isEmpty()) {
            throw new IllegalArgumentException("LocalProvider refuses secrets: it has no isolation");
        }
        if (spec.network().mode() != NetworkPolicy.Mode.UNRESTRICTED) {
            throw new IllegalArgumentException("LocalProvider cannot restrict the network; pass NetworkPolicy.unrestricted()");
        }
        LOG.warning("LocalProvider: running run " + spec.runId() + " WITHOUT any isolation");
        return new Local(spec);
    }

    private static final class Local implements SandboxInstance {
        private final List<Mount> mounts;
        private final Map<String, String> baseEnv;
        private volatile boolean dead;

        Local(SandboxSpec spec) {
            List<Mount> ms = new ArrayList<>(spec.mounts());
            ms.sort(Comparator.comparingInt((Mount m) -> m.sandboxPath().length()).reversed());
            this.mounts = ms;
            this.baseEnv = spec.env();
        }

        String translate(String s) {
            for (Mount m : mounts) {
                if (s.equals(m.sandboxPath()) || s.startsWith(m.sandboxPath() + "/")) {
                    return m.hostPath() + s.substring(m.sandboxPath().length());
                }
            }
            return s;
        }

        @Override
        public ExecResult exec(List<String> command, String workdir, Map<String, String> env, Duration timeout) {
            if (dead) {
                throw new SandboxDiedException("local sandbox was killed");
            }
            Map<String, String> e = new HashMap<>();
            baseEnv.forEach((k, v) -> e.put(k, translate(v)));
            env.forEach((k, v) -> e.put(k, translate(v)));
            List<String> cmd = command.stream().map(this::translate).toList();
            Proc.Result r = Proc.run(cmd, Path.of(translate(workdir)), e, timeout);
            if (dead) {
                throw new SandboxDiedException("local sandbox was killed during the command");
            }
            return new ExecResult(r.exitCode(), r.stdout(), r.stderr(), r.timedOut());
        }

        @Override
        public boolean isAlive() {
            return !dead;
        }

        @Override
        public void kill() {
            dead = true;
        }

        @Override
        public void close() {
            dead = true;
        }
    }
}
