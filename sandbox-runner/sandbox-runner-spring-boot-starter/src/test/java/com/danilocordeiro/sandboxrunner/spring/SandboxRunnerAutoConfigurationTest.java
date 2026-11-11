package com.danilocordeiro.sandboxrunner.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.danilocordeiro.sandboxrunner.SandboxRunner;
import com.danilocordeiro.sandboxrunner.agent.AgentRunner;
import com.danilocordeiro.sandboxrunner.agent.ClaudeCode;
import com.danilocordeiro.sandboxrunner.agent.CommandAgent;
import com.danilocordeiro.sandboxrunner.provider.LocalProvider;
import com.danilocordeiro.sandboxrunner.spi.SandboxProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SandboxRunnerAutoConfigurationTest {

    final ApplicationContextRunner ctx = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SandboxRunnerAutoConfiguration.class));

    @Test
    void createsARunnerFromProperties() {
        ctx.withPropertyValues("sandbox-runner.image=acme/agent:1", "sandbox-runner.allow=api.anthropic.com",
                        "sandbox-runner.memory=2GB", "sandbox-runner.timeout=10m",
                        "sandbox-runner.gates.verify=mvn -B -q verify", "sandbox-runner.agent.max-turns=20")
                .run(c -> {
                    assertNotNull(c.getBean(SandboxRunner.class));
                    assertEquals("docker", c.getBean(SandboxProvider.class).name());
                    assertTrue(c.getBean(AgentRunner.class) instanceof ClaudeCode);
                });
    }

    @Test
    void userBeansWin() {
        SandboxProvider local = LocalProvider.unsafeForTestsOnly();
        ctx.withBean(SandboxProvider.class, () -> local)
                .withPropertyValues("sandbox-runner.agent.type=command", "sandbox-runner.agent.command=sh,-c,true")
                .run(c -> {
                    assertSame(local, c.getBean(SandboxProvider.class));
                    assertTrue(c.getBean(AgentRunner.class) instanceof CommandAgent);
                });
    }

    @Test
    void missingCredentialFailsAtStartup() {
        ctx.withPropertyValues("sandbox-runner.credentials=SANDBOX_RUNNER_SURELY_UNSET_VARIABLE")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }
}
