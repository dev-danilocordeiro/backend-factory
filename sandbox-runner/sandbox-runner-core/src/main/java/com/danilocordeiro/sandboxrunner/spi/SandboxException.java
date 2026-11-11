package com.danilocordeiro.sandboxrunner.spi;

/** A provider could not create or talk to a sandbox. */
public class SandboxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
