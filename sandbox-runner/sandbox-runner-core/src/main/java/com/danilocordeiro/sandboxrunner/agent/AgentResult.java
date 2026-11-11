package com.danilocordeiro.sandboxrunner.agent;

import java.util.Optional;

/** Outcome of an agent process. {@code output} is raw; the runner redacts it. */
public record AgentResult(boolean ok, int exitCode, boolean timedOut, String output, Optional<AgentReport> report) {
}
