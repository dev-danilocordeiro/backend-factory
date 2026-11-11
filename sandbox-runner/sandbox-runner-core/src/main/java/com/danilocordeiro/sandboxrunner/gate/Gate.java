package com.danilocordeiro.sandboxrunner.gate;

import com.danilocordeiro.sandboxrunner.spi.ExecResult;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A deterministic check run after the agent, <b>inside the sandbox</b>: gates
 * build and test code the agent wrote, so they get the same isolation. A red
 * gate stops the run before anything is merged.
 */
public interface Gate {

    String name();

    GateResult check(SandboxInstance sandbox, String workdir, Duration timeout);

    /** A command that passes when it exits 0. */
    static Gate command(String name, String... command) {
        List<String> cmd = List.of(command);
        if (cmd.isEmpty()) {
            throw new IllegalArgumentException("empty gate command");
        }
        return new Gate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GateResult check(SandboxInstance sandbox, String workdir, Duration timeout) {
                long start = System.nanoTime();
                ExecResult r = sandbox.exec(cmd, workdir, Map.of(), timeout);
                return new GateResult(name, r.ok(), r.exitCode(), r.timedOut(), r.output(),
                        (System.nanoTime() - start) / 1_000_000);
            }
        };
    }
}
