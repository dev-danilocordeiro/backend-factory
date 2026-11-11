package com.danilocordeiro.sandboxrunner.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Resources of one run, released in reverse order of acquisition. Every step
 * runs even if an earlier one fails; failures are collected, not thrown.
 */
public final class Cleanup {

    private record Step(String name, Runnable action) { }

    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<String> failures = new ArrayList<>();
    private int released;

    public synchronized void push(String name, Runnable action) {
        steps.push(new Step(name, action));
    }

    /** Releases everything. Safe to call more than once. */
    public synchronized void releaseAll() {
        while (!steps.isEmpty()) {
            Step s = steps.pop();
            try {
                s.action().run();
                released++;
            } catch (RuntimeException e) {
                failures.add(s.name() + ": " + e.getMessage());
            }
        }
    }

    public synchronized int released() {
        return released;
    }

    public synchronized List<String> failures() {
        return List.copyOf(failures);
    }
}
