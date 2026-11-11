package com.danilocordeiro.sandboxrunner.spi;

/** The sandbox stopped while a command was running in it. */
public class SandboxDiedException extends SandboxException {

    private static final long serialVersionUID = 1L;

    public SandboxDiedException(String message) {
        super(message);
    }
}
