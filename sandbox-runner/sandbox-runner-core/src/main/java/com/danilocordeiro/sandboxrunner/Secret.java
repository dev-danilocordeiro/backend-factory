package com.danilocordeiro.sandboxrunner;

import java.util.Objects;

/**
 * A credential passed to the sandbox as an environment variable. Its value is
 * never part of {@link #toString()}, never passed on a command line, and is
 * redacted from every log, event and {@link RunResult}.
 */
public final class Secret {

    private final String name;
    private final String value;

    private Secret(String name, String value) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid environment variable name: " + name);
        }
        this.name = name;
        this.value = Objects.requireNonNull(value, "value");
    }

    /** Reads {@code name} from this JVM's environment. */
    public static Secret fromEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("environment variable " + name + " is not set");
        }
        return new Secret(name, v);
    }

    /** Wraps a value obtained elsewhere (a vault, a config server). */
    public static Secret of(String name, String value) {
        return new Secret(name, value);
    }

    public String name() {
        return name;
    }

    /** The raw value. Only the provider that injects it into the sandbox should call this. */
    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "Secret[" + name + "=****]";
    }
}
