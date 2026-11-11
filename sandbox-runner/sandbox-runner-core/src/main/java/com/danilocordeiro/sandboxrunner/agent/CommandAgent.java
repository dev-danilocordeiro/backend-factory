package com.danilocordeiro.sandboxrunner.agent;

import com.danilocordeiro.sandboxrunner.spi.ExecResult;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Any command-line agent. The task text is passed in the environment variable
 * {@code SANDBOX_TASK}; success is exit code 0.
 */
public final class CommandAgent implements AgentRunner {

    private final List<String> command;

    private CommandAgent(List<String> command) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        this.command = List.copyOf(command);
    }

    public static CommandAgent of(String... command) {
        return new CommandAgent(List.of(command));
    }

    public static CommandAgent of(List<String> command) {
        return new CommandAgent(command);
    }

    @Override
    public String name() {
        return "command:" + command.getFirst();
    }

    @Override
    public AgentResult run(SandboxInstance sandbox, AgentRequest request) {
        ExecResult r = sandbox.exec(command, request.workdir(), Map.of("SANDBOX_TASK", request.prompt()), request.timeout());
        return new AgentResult(r.ok(), r.exitCode(), r.timedOut(), r.output(), Optional.empty());
    }
}
