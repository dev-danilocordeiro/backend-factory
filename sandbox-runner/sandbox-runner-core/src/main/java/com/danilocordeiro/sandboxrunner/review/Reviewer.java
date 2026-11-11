package com.danilocordeiro.sandboxrunner.review;

/** Reviews a diff before it is delivered. */
public interface Reviewer {

    ReviewOutcome review(String diff, String title);
}
