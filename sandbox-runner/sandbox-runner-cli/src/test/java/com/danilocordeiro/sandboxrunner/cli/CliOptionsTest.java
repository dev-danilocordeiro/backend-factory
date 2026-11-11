package com.danilocordeiro.sandboxrunner.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliOptionsTest {

    @Test
    void parsesARealisticCommandLine() {
        CliOptions o = CliOptions.parse(List.of("--task", "Paginate", "--strategy", "merge:feature/orders",
                "--allow", "api.anthropic.com", "--allow", "repo.maven.apache.org", "--memory", "6g", "--timeout", "45m",
                "--gate", "verify=mvn -B -q verify", "--events", "jsonl", "--max-turns", "30"));
        assertEquals("new-branch-and-merge", o.branchStrategy().name());
        assertEquals(NetworkPolicy.Mode.ALLOWLIST, o.network().mode());
        assertEquals(2, o.network().allowedDomains().size());
        assertEquals(6L << 30, o.memory.bytes());
        assertEquals(Duration.ofMinutes(45), o.timeout);
        assertEquals("mvn -B -q verify", o.gates.get("verify"));
        assertTrue(o.events);
    }

    @Test
    void defaultsAreSafe() {
        CliOptions o = CliOptions.parse(List.of("--task", "x"));
        assertEquals(NetworkPolicy.Mode.NONE, o.network().mode());
        assertEquals("patch-only", o.branchStrategy().name());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(List.of()));
        assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(List.of("--task", "x", "--bogus", "1")));
        assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(List.of("--task", "x", "--gate", "nocommand")));
        assertThrows(CliOptions.UsageException.class,
                () -> CliOptions.parse(List.of("--task", "x", "--allow", "a.com", "--unrestricted-network")));
    }

    @Test
    void usageErrorsExitWith2() {
        var err = new ByteArrayOutputStream();
        assertEquals(2, Main.run(List.of("run"), new PrintStream(new ByteArrayOutputStream()), new PrintStream(err)));
        assertTrue(err.toString().contains("--task is required"));
    }
}
