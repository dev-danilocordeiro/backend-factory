package com.danilocordeiro.sandboxrunner.spi;

/** Creates isolated environments. Implementations: Docker, Podman, Local (tests only). */
public interface SandboxProvider {

    SandboxInstance create(SandboxSpec spec);

    /** Short name for logs and events, e.g. {@code docker}. */
    String name();

    /** Removes sandboxes left behind by runs whose JVM died. Returns how many were removed. */
    default int reapOrphans() {
        return 0;
    }
}
