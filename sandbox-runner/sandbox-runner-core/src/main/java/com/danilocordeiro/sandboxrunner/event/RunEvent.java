package com.danilocordeiro.sandboxrunner.event;

import com.danilocordeiro.sandboxrunner.RunStatus;
import java.time.Instant;

/** Progress of one run. All text is already redacted. */
public sealed interface RunEvent {

    String runId();

    Instant at();

    record Started(String runId, Instant at, String task, String strategy) implements RunEvent { }

    record WorkspaceReady(String runId, Instant at, String branch, String baseCommit) implements RunEvent { }

    record SandboxCreated(String runId, Instant at, String provider) implements RunEvent { }

    record AgentStarted(String runId, Instant at, String agent) implements RunEvent { }

    record AgentFinished(String runId, Instant at, boolean ok, String summary) implements RunEvent { }

    record ChangesCollected(String runId, Instant at, int commits, int changedFiles) implements RunEvent { }

    record GateFinished(String runId, Instant at, String gate, boolean ok, long durationMs) implements RunEvent { }

    record ReviewFinished(String runId, Instant at, String verdict, boolean blocking) implements RunEvent { }

    record Delivered(String runId, Instant at, RunStatus status, String branch) implements RunEvent { }

    record CleanedUp(String runId, Instant at, int resources, int failures) implements RunEvent { }

    record Finished(String runId, Instant at, RunStatus status, long durationMs) implements RunEvent { }
}
