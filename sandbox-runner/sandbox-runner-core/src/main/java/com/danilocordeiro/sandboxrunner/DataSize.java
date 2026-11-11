package com.danilocordeiro.sandboxrunner;

/** A size in bytes. */
public record DataSize(long bytes) {

    public DataSize {
        if (bytes <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
    }

    public static DataSize ofMegabytes(long mb) {
        return new DataSize(mb * 1024 * 1024);
    }

    public static DataSize ofGigabytes(long gb) {
        return new DataSize(gb * 1024 * 1024 * 1024);
    }

    /** Docker/Podman notation, e.g. {@code 512m}. */
    public String toDockerString() {
        return (bytes / (1024 * 1024)) + "m";
    }
}
