package com.danilocordeiro.sandboxrunner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.danilocordeiro.sandboxrunner.git.Imported;
import com.danilocordeiro.sandboxrunner.git.PolicyException;
import com.danilocordeiro.sandboxrunner.internal.Redactor;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangePolicyTest {

    static final Secret KEY = Secret.of("API_KEY", "sk-test-1234567890abcdef");
    static final Redactor R = new Redactor(List.of(KEY));

    static Imported change(List<String> paths, String diff) {
        return new Imported(null, "r", "base", List.of(new CommitInfo("a", "b", "c")), diff, paths, List.of());
    }

    @Test
    void allowsOrdinaryChanges() {
        assertDoesNotThrow(() -> ChangePolicy.defaults().check(change(List.of("src/Main.java"), "+x"), R, DataSize.ofMegabytes(1)));
    }

    @Test
    void deniesGitAndCiControlFiles() {
        for (String p : List.of(".gitattributes", "sub/dir/.gitattributes", ".gitmodules", ".github/workflows/ci.yml")) {
            assertThrows(PolicyException.class,
                    () -> ChangePolicy.defaults().check(change(List.of(p), "+x"), R, DataSize.ofMegabytes(1)), p);
        }
    }

    @Test
    void deniesSecretsInTheDiffInAnyCommonEncoding() {
        String b64 = Base64.getEncoder().encodeToString(KEY.reveal().getBytes());
        for (String d : List.of("+key=" + KEY.reveal(), "+key=" + b64)) {
            assertThrows(PolicyException.class, () -> ChangePolicy.defaults().check(change(List.of("a"), d), R, DataSize.ofMegabytes(1)));
        }
    }

    @Test
    void deniesOversizedDiffsAndSubmodules() {
        assertThrows(PolicyException.class,
                () -> ChangePolicy.defaults().check(change(List.of("a"), "x".repeat(2_000_000)), R, DataSize.ofMegabytes(1)));
        Imported sub = new Imported(null, "r", "b", List.of(new CommitInfo("a", "b", "c")), "", List.of("lib"), List.of("lib"));
        assertThrows(PolicyException.class, () -> ChangePolicy.defaults().check(sub, R, DataSize.ofMegabytes(1)));
    }

    @Test
    void redactorMasksAllForms() {
        String b64 = Base64.getEncoder().encodeToString(KEY.reveal().getBytes());
        assertEquals("a **** b **** c", R.redact("a " + KEY.reveal() + " b " + b64 + " c"));
        assertEquals("Secret[API_KEY=****]", KEY.toString());
    }
}
