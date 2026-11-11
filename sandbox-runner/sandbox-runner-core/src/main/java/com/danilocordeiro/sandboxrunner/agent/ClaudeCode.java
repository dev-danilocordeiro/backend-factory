package com.danilocordeiro.sandboxrunner.agent;

import com.danilocordeiro.sandboxrunner.spi.ExecResult;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Claude Code in headless mode: {@code claude -p <task> --output-format json}.
 *
 * <p>It runs with {@code --permission-mode bypassPermissions} because nothing
 * inside a sandbox can answer a permission prompt, and the sandbox, not the
 * agent's permission system, is the security boundary. Never use this agent
 * with {@code LocalProvider}: there the bypass would apply to your machine.
 * Turn and budget limits are passed through to the CLI.
 */
public final class ClaudeCode implements AgentRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String binary;
    private final int maxTurns;
    private final double maxBudgetUsd;
    private final String model;
    private final String appendSystemPrompt;

    private ClaudeCode(String binary, int maxTurns, double maxBudgetUsd, String model, String appendSystemPrompt) {
        this.binary = binary;
        this.maxTurns = maxTurns;
        this.maxBudgetUsd = maxBudgetUsd;
        this.model = model;
        this.appendSystemPrompt = appendSystemPrompt;
    }

    /** Defaults: {@code claude} on PATH, 50 turns, no budget cap, the CLI's default model. */
    public static ClaudeCode headless() {
        return new ClaudeCode("claude", 50, 0, null, DEFAULT_RULES);
    }

    static final String DEFAULT_RULES = """
            You are running unattended in an isolated sandbox. Work only inside the current directory.
            Commit your work with git when you are done, with a short message that says what changed.
            Do not try to push, open pull requests or reach services other than the ones you need.""";

    public ClaudeCode binary(String b) {
        return new ClaudeCode(b, maxTurns, maxBudgetUsd, model, appendSystemPrompt);
    }

    public ClaudeCode maxTurns(int n) {
        return new ClaudeCode(binary, n, maxBudgetUsd, model, appendSystemPrompt);
    }

    public ClaudeCode maxBudgetUsd(double usd) {
        return new ClaudeCode(binary, maxTurns, usd, model, appendSystemPrompt);
    }

    public ClaudeCode model(String m) {
        return new ClaudeCode(binary, maxTurns, maxBudgetUsd, m, appendSystemPrompt);
    }

    public ClaudeCode appendSystemPrompt(String p) {
        return new ClaudeCode(binary, maxTurns, maxBudgetUsd, model, p);
    }

    @Override
    public String name() {
        return "claude-code";
    }

    List<String> command(String prompt) {
        List<String> c = new ArrayList<>(List.of(binary, "-p", prompt, "--output-format", "json",
                "--permission-mode", "bypassPermissions", "--max-turns", Integer.toString(maxTurns)));
        if (maxBudgetUsd > 0) {
            c.addAll(List.of("--max-budget-usd", String.format(java.util.Locale.ROOT, "%.2f", maxBudgetUsd)));
        }
        if (model != null) {
            c.addAll(List.of("--model", model));
        }
        if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
            c.addAll(List.of("--append-system-prompt", appendSystemPrompt));
        }
        return c;
    }

    @Override
    public AgentResult run(SandboxInstance sandbox, AgentRequest request) {
        ExecResult r = sandbox.exec(command(request.prompt()), request.workdir(), Map.of(), request.timeout());
        Optional<JsonNode> json = parse(r.stdout());
        Optional<AgentReport> report = json.map(j -> new AgentReport(j.path("num_turns").asInt(),
                j.path("total_cost_usd").asDouble(), j.path("session_id").asText(""), j.path("subtype").asText("")));
        boolean ok = r.ok() && json.isPresent()
                && !json.get().path("is_error").asBoolean(false)
                && "success".equals(json.get().path("subtype").asText());
        String output = json.map(j -> j.path("result").asText("")).filter(s -> !s.isBlank()).orElse(r.output());
        return new AgentResult(ok, r.exitCode(), r.timedOut(), output, report);
    }

    /** The CLI prints one JSON object; tolerate stray lines before it. */
    static Optional<JsonNode> parse(String stdout) {
        String[] lines = stdout.strip().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].strip();
            if (l.startsWith("{")) {
                try {
                    JsonNode n = JSON.readTree(l);
                    if ("result".equals(n.path("type").asText())) {
                        return Optional.of(n);
                    }
                } catch (java.io.IOException ignored) {
                    // not the result line
                }
            }
        }
        return Optional.empty();
    }
}
