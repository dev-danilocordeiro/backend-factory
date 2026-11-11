package com.danilocordeiro.sandboxrunner.spring;

import com.danilocordeiro.sandboxrunner.Limits;
import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import com.danilocordeiro.sandboxrunner.SandboxRunner;
import com.danilocordeiro.sandboxrunner.Secret;
import com.danilocordeiro.sandboxrunner.agent.AgentRunner;
import com.danilocordeiro.sandboxrunner.agent.ClaudeCode;
import com.danilocordeiro.sandboxrunner.agent.CommandAgent;
import com.danilocordeiro.sandboxrunner.event.RunListener;
import com.danilocordeiro.sandboxrunner.gate.Gate;
import com.danilocordeiro.sandboxrunner.git.BranchProtection;
import com.danilocordeiro.sandboxrunner.provider.DockerProvider;
import com.danilocordeiro.sandboxrunner.review.HttpReviewer;
import com.danilocordeiro.sandboxrunner.review.Reviewer;
import com.danilocordeiro.sandboxrunner.spi.SandboxProvider;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * A {@link SandboxRunner} bean from {@code sandbox-runner.*} properties. Any
 * {@link SandboxProvider}, {@link AgentRunner} or {@link Reviewer} bean you
 * declare replaces the property-based one; every {@link RunListener} and
 * {@link Gate} bean is added.
 */
@AutoConfiguration
@ConditionalOnClass(SandboxRunner.class)
@EnableConfigurationProperties(SandboxRunnerProperties.class)
public class SandboxRunnerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SandboxProvider sandboxProvider(SandboxRunnerProperties p) {
        return switch (p.getProvider()) {
            case "docker" -> DockerProvider.image(p.getImage());
            case "podman" -> DockerProvider.podman(p.getImage());
            default -> throw new IllegalArgumentException("sandbox-runner.provider must be docker or podman");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    AgentRunner sandboxAgent(SandboxRunnerProperties p) {
        SandboxRunnerProperties.Agent a = p.getAgent();
        if (a.getType().equals("command")) {
            return CommandAgent.of(a.getCommand());
        }
        if (!a.getType().equals("claude")) {
            throw new IllegalArgumentException("sandbox-runner.agent.type must be claude or command");
        }
        ClaudeCode c = ClaudeCode.headless().maxTurns(a.getMaxTurns());
        if (a.getMaxBudgetUsd() > 0) {
            c = c.maxBudgetUsd(a.getMaxBudgetUsd());
        }
        return a.getModel() == null ? c : c.model(a.getModel());
    }

    @Bean
    @ConditionalOnMissingBean
    SandboxRunner sandboxRunner(SandboxRunnerProperties p, SandboxProvider provider, AgentRunner agent,
                                ObjectProvider<RunListener> listeners, ObjectProvider<Gate> gates,
                                ObjectProvider<Reviewer> reviewer) {
        NetworkPolicy network = p.isUnrestrictedNetwork() ? NetworkPolicy.unrestricted()
                : p.getAllow().isEmpty() ? NetworkPolicy.none() : NetworkPolicy.allow(p.getAllow().toArray(String[]::new));
        SandboxRunner.Builder b = SandboxRunner.builder()
                .provider(provider)
                .agent(agent)
                .network(network)
                .limits(Limits.of(p.getCpus(), new com.danilocordeiro.sandboxrunner.DataSize(p.getMemory().toBytes()), p.getTimeout()))
                .retainOnConflict(p.isRetainOnConflict());
        p.getCredentials().forEach(name -> b.credential(Secret.fromEnv(name)));
        p.getGates().forEach((name, cmd) -> b.gate(Gate.command(name, "sh", "-c", cmd)));
        gates.orderedStream().forEach(b::gate);
        listeners.orderedStream().forEach(b::listener);
        Reviewer r = reviewer.getIfAvailable();
        if (r != null) {
            b.reviewer(r);
        } else if (p.getReviewerUrl() != null) {
            b.reviewer(HttpReviewer.at(URI.create(p.getReviewerUrl())));
        }
        if (!p.getProtectedBranches().isEmpty()) {
            b.protection(BranchProtection.patterns(p.getProtectedBranches()));
        }
        if (agent instanceof ClaudeCode) {
            b.env("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        }
        return b.build();
    }
}
