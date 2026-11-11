package com.danilocordeiro.sandboxrunner.gate;

/** Outcome of one gate. {@code output} is a redacted tail. */
public record GateResult(String name, boolean ok, int exitCode, boolean timedOut, String output, long durationMs) {
}
