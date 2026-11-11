package com.danilocordeiro.sandboxrunner;

import com.danilocordeiro.sandboxrunner.agent.AgentReport;
import com.danilocordeiro.sandboxrunner.gate.GateResult;
import com.danilocordeiro.sandboxrunner.review.ReviewOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Everything a run produced. Text fields (detail, diff, gate output, logs)
 * are redacted: no credential given to the sandbox appears in them.
 *
 * @param branch  the branch holding the result: the target after a merge,
 *                sandbox/&lt;run&gt; for the worktree strategy, empty otherwise
 * @param detail  one-line explanation of the status
 */
public record RunResult(String runId, RunStatus status, String detail, Optional<String> branch,
                        List<CommitInfo> commits, String diff, List<GateResult> gates,
                        Optional<ReviewOutcome> review, Optional<AgentReport> agent, Duration duration,
                        List<String> logs) {

    public RunResult {
        commits = List.copyOf(commits);
        gates = List.copyOf(gates);
        logs = List.copyOf(logs);
    }
}
