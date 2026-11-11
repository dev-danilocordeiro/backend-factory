package com.danilocordeiro.sandboxrunner.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danilocordeiro.sandboxrunner.internal.Proc;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI end to end with Docker and a fake agent. Needs the core module's test image. */
@Tag("docker")
class CliDockerIT {

    @TempDir
    Path tmp;

    static void git(Path dir, String... args) {
        var cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Proc.Result r = Proc.run(cmd, null, Map.of(), Duration.ofSeconds(30));
        assertTrue(r.ok(), r.output());
    }

    @Test
    void runsATaskAndPrintsJsonAndEvents() throws IOException {
        Proc.Result build = Proc.run(List.of("docker", "build", "-q", "-t", "sandbox-runner-test-agent:0.1",
                Path.of("../sandbox-runner-core/src/test/resources/test-agent").toAbsolutePath().toString()),
                null, Map.of(), Duration.ofMinutes(10));
        assertTrue(build.ok(), build.output());
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@x", "commit", "-qm", "init");
        git(repo, "branch", "work");

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.run(List.of("run", "--repo", repo.toString(), "--task", "add b",
                "--strategy", "merge:work", "--image", "sandbox-runner-test-agent:0.1",
                "--agent", "cmd:echo b > b.txt && git add -A && git -c user.name=f -c user.email=f@x commit -qm b",
                "--gate", "has-b=test -f b.txt", "--timeout", "90s", "--memory", "256m", "--events", "jsonl"),
                new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, out + "\n" + err);
        JsonNode result = Main.JSON.readTree(out.toString());
        assertEquals("MERGED", result.path("status").asText());
        assertEquals("has-b", result.path("gates").get(0).path("name").asText());
        List<String> events = err.toString().lines().map(l -> {
            try {
                return Main.JSON.readTree(l).path("event").asText();
            } catch (IOException e) {
                return "unparseable: " + l;
            }
        }).toList();
        assertEquals("Started", events.getFirst());
        assertEquals("Finished", events.getLast());
        assertTrue(events.contains("Delivered"), events.toString());
    }
}
