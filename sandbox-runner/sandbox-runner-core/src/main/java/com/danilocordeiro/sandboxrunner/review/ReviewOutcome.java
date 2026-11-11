package com.danilocordeiro.sandboxrunner.review;

import java.util.List;
import java.util.Optional;

/**
 * @param verdict  APPROVE, COMMENT, REQUEST_CHANGES, or UNAVAILABLE when the review could not be obtained
 * @param blocking whether the run must stop
 */
public record ReviewOutcome(String verdict, boolean blocking, String summary, List<Finding> findings,
                            Optional<String> error) {

    public ReviewOutcome {
        findings = List.copyOf(findings);
    }
}
