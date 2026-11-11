package com.danilocordeiro.sandboxrunner.cli;

import com.danilocordeiro.sandboxrunner.RunResult;
import com.danilocordeiro.sandboxrunner.SandboxRunner;
import com.danilocordeiro.sandboxrunner.agent.CommandAgent;
import com.danilocordeiro.sandboxrunner.provider.DockerProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/** Entry point of the shaded jar. */
public final class Main {

    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(List.of(args), System.out, System.err));
    }

    static int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty() || args.getFirst().equals("--help") || args.getFirst().equals("-h")) {
            err.println(CliOptions.USAGE);
            return args.isEmpty() ? 2 : 0;
        }
        try {
            return switch (args.getFirst()) {
                case "run" -> runTask(CliOptions.parse(args.subList(1, args.size())), out, err);
                case "reap" -> reap(args.subList(1, args.size()), out);
                default -> throw new CliOptions.UsageException("unknown command " + args.getFirst());
            };
        } catch (CliOptions.UsageException | NumberFormatException e) {
            err.println("sandbox-runner: " + e.getMessage());
            err.println(CliOptions.USAGE);
            return 2;
        } catch (RuntimeException e) {
            err.println("sandbox-runner: " + e.getMessage());
            return 1;
        }
    }

    private static int runTask(CliOptions o, PrintStream out, PrintStream err) {
        SandboxRunner.Builder b = o.builder();
        if (o.events) {
            b.listener(e -> {
                ObjectNode n = JSON.valueToTree(e);
                n.put("event", e.getClass().getSimpleName());
                synchronized (err) {
                    err.println(n);
                }
            });
        }
        RunResult r = b.build().run(o.task());
        try {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return r.status().isSuccess() ? 0 : 3;
    }

    private static int reap(List<String> args, PrintStream out) {
        Path repo = Path.of(".");
        boolean podman = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--repo" -> repo = Path.of(args.get(++i));
                case "--podman" -> podman = true;
                default -> throw new CliOptions.UsageException("unknown option " + args.get(i));
            }
        }
        // The agent is irrelevant for reaping; the builder just requires one.
        int n = SandboxRunner.builder()
                .provider(podman ? DockerProvider.podman("unused") : DockerProvider.image("unused"))
                .agent(CommandAgent.of("true")).build().reap(repo);
        out.println("removed " + n + " orphaned sandbox resource(s)");
        return 0;
    }
}
