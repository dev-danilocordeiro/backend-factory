package com.danilocordeiro.sandboxrunner.agent;

import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;

/** The command-line agent that runs inside the sandbox. */
public interface AgentRunner {

    String name();

    AgentResult run(SandboxInstance sandbox, AgentRequest request);
}
