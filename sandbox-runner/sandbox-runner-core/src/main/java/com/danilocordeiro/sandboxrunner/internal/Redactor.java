package com.danilocordeiro.sandboxrunner.internal;

import com.danilocordeiro.sandboxrunner.Secret;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces secret values, and their base64 and URL-encoded forms, with
 * {@code ****}. A secret transformed in any other way is not caught; see the
 * README security model.
 */
public final class Redactor {

    public static final String MASK = "****";
    private static final int MIN_LENGTH = 6;
    private final List<String> needles;

    public Redactor(Collection<Secret> secrets) {
        Set<String> s = new LinkedHashSet<>();
        for (Secret secret : secrets) {
            String v = secret.reveal();
            if (v.length() < MIN_LENGTH) {
                continue; // too short to redact without destroying unrelated text
            }
            s.add(v);
            s.add(Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)));
            s.add(Base64.getEncoder().withoutPadding().encodeToString(v.getBytes(StandardCharsets.UTF_8)));
            s.add(URLEncoder.encode(v, StandardCharsets.UTF_8));
        }
        List<String> sorted = new ArrayList<>(s);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        this.needles = List.copyOf(sorted);
    }

    public static Redactor none() {
        return new Redactor(List.of());
    }

    public String redact(String text) {
        if (text == null || needles.isEmpty()) {
            return text;
        }
        String out = text;
        for (String n : needles) {
            out = out.replace(n, MASK);
        }
        return out;
    }

    /** True when text contains any secret form. */
    public boolean leaks(String text) {
        if (text == null) {
            return false;
        }
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
