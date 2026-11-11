package com.danilocordeiro.sandboxrunner.review;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A reviewer finding, as returned by ai-code-reviewer. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(String severity, String category, String title, String description, Integer line,
                      String suggestion, String reviewer) {
}
