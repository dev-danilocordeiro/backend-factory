package com.danilocordeiro.sandboxrunner.cli;

import com.danilocordeiro.sandboxrunner.DataSize;
import com.danilocordeiro.sandboxrunner.Limits;
import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import com.danilocordeiro.sandboxrunner.Secret;
import com.danilocordeiro.sandboxrunner.Task;
import com.danilocordeiro.sandboxrunner.agent.AgentRunner;
import com.danilocordeiro.sandboxrunner.agent.ClaudeCode;
import com.danilocordeiro.sandboxrunner.agent.CommandAgent;
import com.danilocordeiro.sandboxrunner.gate.Gate;
import com.danilocordeiro.sandboxrunner.git.BranchStrategy;
import com.danilocordeiro.sandboxrunner.provider.DockerProvider;
import com.danilocordeiro.sandboxrunner.review.HttpReviewer;
import com.danilocordeiro.sandboxrunner.SandboxRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parsed flags of {@code sandbox-runner run}. */
final class CliOptions {

    static final String USAGE = """
            usage: sandbox-runner run --task TEXT [options]
                   sandbox-runner reap [--repo DIR] [--podman]

            run options:
              --repo DIR                 repository (default: current directory)
              --task TEXT                short title; also the prompt unless --prompt-file is given
              --prompt-file FILE         full prompt for the agent
              --strategy S               merge:<branch> | worktree | patch   (default: patch)
              --base REF                 start from REF instead of the strategy default
              --image IMAGE              agent image (default: sandbox-runner/agent-jdk21:0.1)
              --podman                   use podman instead of docker
              --agent A                  claude (default) | cmd:<shell command>
              --max-turns N              Claude Code turn limit (default 50)
              --max-budget-usd X         Claude Code budget cap
              --model M                  Claude Code model
              --credential NAME          pass env var NAME as a secret (repeatable)
              --allow DOMAIN             open HTTPS to DOMAIN through the egress proxy (repeatable)
              --unrestricted-network     no network isolation (explicit opt-out)
              --cpus N --memory 4g --timeout 30m
              --gate NAME=COMMAND        shell command run in the sandbox after the agent (repeatable)
              --reviewer URL             e.g. http://localhost:8080/api/review
              --retain-on-conflict       keep the sandbox/* branch when the merge conflicts
              --events jsonl             progress events as JSON lines on stderr
            exit codes: 0 merged/branch/patch ready, 3 stopped (see status), 2 usage, 1 error""";

    Path repo = Path.of(".");
    String task;
    String prompt;
    String strategy = "patch";
    String base;
    String image = com.danilocordeiro.sandboxrunner.Sandbox.DEFAULT_IMAGE;
    boolean podman;
    String agent = "claude";
    int maxTurns = 50;
    double maxBudgetUsd;
    String model;
    final List<String> credentials = new ArrayList<>();
    final List<String> allow = new ArrayList<>();
    boolean unrestricted;
    double cpus = 2;
    DataSize memory = DataSize.ofGigabytes(4);
    Duration timeout = Duration.ofMinutes(30);
    final Map<String, String> gates = new LinkedHashMap<>();
    String reviewer;
    boolean retainOnConflict;
    boolean events;

    static CliOptions parse(List<String> args) {
        CliOptions o = new CliOptions();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--podman" -> o.podman = true;
                case "--unrestricted-network" -> o.unrestricted = true;
                case "--retain-on-conflict" -> o.retainOnConflict = true;
                default -> {
                    if (i + 1 >= args.size()) {
                        throw new UsageException("missing value for " + a);
                    }
                    String v = args.get(++i);
                    switch (a) {
                        case "--repo" -> o.repo = Path.of(v);
                        case "--task" -> o.task = v;
                        case "--prompt-file" -> o.prompt = read(Path.of(v));
                        case "--strategy" -> o.strategy = v;
                        case "--base" -> o.base = v;
                        case "--image" -> o.image = v;
                        case "--agent" -> o.agent = v;
                        case "--max-turns" -> o.maxTurns = Integer.parseInt(v);
                        case "--max-budget-usd" -> o.maxBudgetUsd = Double.parseDouble(v);
                        case "--model" -> o.model = v;
                        case "--credential" -> o.credentials.add(v);
                        case "--allow" -> o.allow.add(v);
                        case "--cpus" -> o.cpus = Double.parseDouble(v);
                        case "--memory" -> o.memory = size(v);
                        case "--timeout" -> o.timeout = duration(v);
                        case "--gate" -> {
                            int eq = v.indexOf('=');
                            if (eq < 1) {
                                throw new UsageException("--gate expects NAME=COMMAND");
                            }
                            o.gates.put(v.substring(0, eq), v.substring(eq + 1));
                        }
                        case "--reviewer" -> o.reviewer = v;
                        case "--events" -> {
                            if (!v.equals("jsonl")) {
                                throw new UsageException("--events supports only jsonl");
                            }
                            o.events = true;
                        }
                        default -> throw new UsageException("unknown option " + a);
                    }
                }
            }
        }
        if (o.task == null || o.task.isBlank()) {
            throw new UsageException("--task is required");
        }
        if (o.unrestricted && !o.allow.isEmpty()) {
            throw new UsageException("--allow and --unrestricted-network are exclusive");
        }
        return o;
    }

    static DataSize size(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        return switch (s.charAt(s.length() - 1)) {
            case 'g' -> DataSize.ofGigabytes(n);
            case 'm' -> DataSize.ofMegabytes(n);
            default -> throw new UsageException("size must end in m or g: " + v);
        };
    }

    static Duration duration(String v) {
        String s = v.toLowerCase(Locale.ROOT);
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        return switch (s.charAt(s.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            default -> throw new UsageException("duration must end in s, m or h: " + v);
        };
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    BranchStrategy branchStrategy() {
        if (strategy.startsWith("merge:")) {
            return BranchStrategy.newBranchAndMerge(strategy.substring("merge:".length()));
        }
        return switch (strategy) {
            case "worktree" -> BranchStrategy.worktree();
            case "patch" -> BranchStrategy.patchOnly();
            default -> throw new UsageException("unknown strategy " + strategy);
        };
    }

    AgentRunner agentRunner() {
        if (agent.startsWith("cmd:")) {
            return CommandAgent.of("sh", "-c", agent.substring(4));
        }
        if (!agent.equals("claude")) {
            throw new UsageException("unknown agent " + agent);
        }
        ClaudeCode c = ClaudeCode.headless().maxTurns(maxTurns);
        if (maxBudgetUsd > 0) {
            c = c.maxBudgetUsd(maxBudgetUsd);
        }
        return model == null ? c : c.model(model);
    }

    NetworkPolicy network() {
        if (unrestricted) {
            return NetworkPolicy.unrestricted();
        }
        return allow.isEmpty() ? NetworkPolicy.none() : NetworkPolicy.allow(allow.toArray(String[]::new));
    }

    Task task() {
        Task t = Task.of(task, repo, branchStrategy());
        if (prompt != null) {
            t = t.withPrompt(prompt);
        }
        return base == null ? t : t.withBase(base);
    }

    SandboxRunner.Builder builder() {
        SandboxRunner.Builder b = SandboxRunner.builder()
                .provider(podman ? DockerProvider.podman(image) : DockerProvider.image(image))
                .agent(agentRunner())
                .network(network())
                .limits(Limits.of(cpus, memory, timeout))
                .retainOnConflict(retainOnConflict);
        credentials.forEach(n -> b.credential(Secret.fromEnv(n)));
        gates.forEach((name, cmd) -> b.gate(Gate.command(name, "sh", "-c", cmd)));
        if (reviewer != null) {
            b.reviewer(HttpReviewer.at(URI.create(reviewer)));
        }
        if (agent.equals("claude")) {
            b.env("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        }
        return b;
    }

    /** Bad command line. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String m) {
            super(m);
        }
    }
}
