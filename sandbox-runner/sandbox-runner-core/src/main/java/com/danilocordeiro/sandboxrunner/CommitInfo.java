package com.danilocordeiro.sandboxrunner;

/** A commit produced by a run. */
public record CommitInfo(String sha, String author, String subject) {
}
