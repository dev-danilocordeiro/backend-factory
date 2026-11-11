package com.danilocordeiro.sandboxrunner.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeCodeTest {

    @Test
    void buildsHeadlessCommand() {
        List<String> c = ClaudeCode.headless().maxTurns(7).maxBudgetUsd(1.5).model("sonnet").appendSystemPrompt("").command("do x");
        assertEquals(List.of("claude", "-p", "do x", "--output-format", "json", "--permission-mode", "bypassPermissions",
                "--max-turns", "7", "--max-budget-usd", "1.50", "--model", "sonnet"), c);
    }

    @Test
    void parsesTheResultLine() {
        var j = ClaudeCode.parse("warning: something\n{\"type\":\"result\",\"subtype\":\"success\",\"num_turns\":3,\"total_cost_usd\":0.2}\n");
        assertTrue(j.isPresent());
        assertEquals(3, j.get().path("num_turns").asInt());
        assertTrue(ClaudeCode.parse("no json").isEmpty());
    }
}
