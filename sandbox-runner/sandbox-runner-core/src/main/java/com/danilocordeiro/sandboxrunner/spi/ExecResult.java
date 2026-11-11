package com.danilocordeiro.sandboxrunner.spi;

/** Result of a command run inside a sandbox. Output is not yet redacted. */
public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    public boolean ok() {
        return exitCode == 0 && !timedOut;
    }

    public String output() {
        return stderr.isBlank() ? stdout : stdout + (stdout.isEmpty() ? "" : "\n") + stderr;
    }
}
