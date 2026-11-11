package com.danilocordeiro.sandboxrunner;

import java.util.List;
import java.util.Locale;

/**
 * Outbound network access of the sandbox. The default is {@link #none()}.
 * {@link #allow(String...)} routes HTTP(S) through an egress proxy that only
 * opens connections to the listed domains; everything else, including non-HTTP
 * protocols, has no route out.
 */
public record NetworkPolicy(Mode mode, List<String> allowedDomains) {

    /** How the sandbox reaches the network. */
    public enum Mode { NONE, ALLOWLIST, UNRESTRICTED }

    public NetworkPolicy {
        allowedDomains = List.copyOf(allowedDomains);
        for (String d : allowedDomains) {
            if (!d.matches("(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) {
                throw new IllegalArgumentException("not a domain (lower case, optional leading *.): " + d);
            }
        }
        if (mode == Mode.ALLOWLIST && allowedDomains.isEmpty()) {
            throw new IllegalArgumentException("allowlist needs at least one domain; use none()");
        }
    }

    public static NetworkPolicy none() {
        return new NetworkPolicy(Mode.NONE, List.of());
    }

    public static NetworkPolicy allow(String... domains) {
        return new NetworkPolicy(Mode.ALLOWLIST, List.of(domains).stream().map(d -> d.toLowerCase(Locale.ROOT)).toList());
    }

    /** No isolation from the network. Must be chosen explicitly. */
    public static NetworkPolicy unrestricted() {
        return new NetworkPolicy(Mode.UNRESTRICTED, List.of());
    }
}
