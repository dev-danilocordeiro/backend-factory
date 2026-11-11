package com.danilocordeiro.sandboxrunner.event;

/**
 * Receives progress events. Called on the run's thread; keep it fast. An
 * exception thrown by a listener is logged in the run and otherwise ignored.
 */
@FunctionalInterface
public interface RunListener {

    void onEvent(RunEvent event);
}
