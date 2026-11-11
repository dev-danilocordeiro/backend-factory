package com.danilocordeiro.sandboxrunner.agent;

/** What an agent reported about itself, when it reports anything (Claude Code does). */
public record AgentReport(int turns, double costUsd, String sessionId, String subtype) {
}
