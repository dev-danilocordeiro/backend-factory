package com.danilocordeiro.sandboxrunner.agent;

import java.time.Duration;

/** One agent invocation: the task text, where to start, and how long it may take. */
public record AgentRequest(String prompt, String workdir, Duration timeout) {
}
