package com.danilocordeiro.sandboxrunner.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A live sandbox. {@link #close()} destroys it and is idempotent. */
public interface SandboxInstance extends AutoCloseable {

    /**
     * Runs a command inside the sandbox.
     *
     * @param workdir directory inside the sandbox, normally a mount's sandbox path
     * @throws SandboxDiedException if the sandbox is gone before or during the call
     */
    ExecResult exec(List<String> command, String workdir, Map<String, String> env, Duration timeout);

    boolean isAlive();

    /** Stops the sandbox immediately, e.g. when the run deadline passes. */
    void kill();

    @Override
    void close();
}
