package com.danilocordeiro.sandboxrunner;

import java.time.Duration;
import java.util.Objects;

/**
 * Resource limits for one run. The timeout covers the whole run (agent,
 * gates, review); when it is exceeded the sandbox is destroyed.
 */
public record Limits(double cpus, DataSize memory, int pids, Duration timeout, DataSize maxDiff) {

    public Limits {
        if (cpus <= 0) {
            throw new IllegalArgumentException("cpus must be positive");
        }
        if (pids <= 0) {
            throw new IllegalArgumentException("pids must be positive");
        }
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(maxDiff, "maxDiff");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static Limits of(double cpus, DataSize memory, Duration timeout) {
        return new Limits(cpus, memory, 512, timeout, DataSize.ofMegabytes(5));
    }

    public static Limits defaults() {
        return of(2.0, DataSize.ofGigabytes(4), Duration.ofMinutes(30));
    }

    public Limits withPids(int p) {
        return new Limits(cpus, memory, p, timeout, maxDiff);
    }

    public Limits withMaxDiff(DataSize d) {
        return new Limits(cpus, memory, pids, timeout, d);
    }
}
