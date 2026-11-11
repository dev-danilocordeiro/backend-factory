package com.danilocordeiro.sandboxrunner.git;

/** The agent's result breaks a rule (forbidden path, size, history, integrity). */
public class PolicyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PolicyException(String message) {
        super(message);
    }
}
