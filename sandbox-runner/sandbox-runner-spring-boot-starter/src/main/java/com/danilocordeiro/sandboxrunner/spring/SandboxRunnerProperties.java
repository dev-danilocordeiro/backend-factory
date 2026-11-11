package com.danilocordeiro.sandboxrunner.spring;

import com.danilocordeiro.sandboxrunner.Sandbox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** {@code sandbox-runner.*} properties. Defaults match the library's safe defaults. */
@ConfigurationProperties("sandbox-runner")
public class SandboxRunnerProperties {

    /** docker or podman. */
    private String provider = "docker";
    /** Agent image; pin it by digest. */
    private String image = Sandbox.DEFAULT_IMAGE;
    private final Agent agent = new Agent();
    /** Names of environment variables passed into the sandbox as secrets. */
    private List<String> credentials = new ArrayList<>();
    /** Domains reachable over HTTPS through the egress proxy. Empty means no network. */
    private List<String> allow = new ArrayList<>();
    /** Disable network isolation entirely. */
    private boolean unrestrictedNetwork;
    private double cpus = 2;
    private DataSize memory = DataSize.ofGigabytes(4);
    private Duration timeout = Duration.ofMinutes(30);
    /** Gate name to shell command, run in the sandbox in this order. */
    private Map<String, String> gates = new LinkedHashMap<>();
    /** Review endpoint, e.g. http://localhost:8080/api/review. */
    private String reviewerUrl;
    private boolean retainOnConflict;
    /** Branch globs that never receive a merge; replaces the library defaults when set. */
    private List<String> protectedBranches = new ArrayList<>();

    /** Agent settings. */
    public static class Agent {
        /** claude or command. */
        private String type = "claude";
        /** For type=command: the command and its arguments. */
        private List<String> command = new ArrayList<>();
        private int maxTurns = 50;
        private double maxBudgetUsd;
        private String model;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public List<String> getCommand() {
            return command;
        }

        public void setCommand(List<String> command) {
            this.command = command;
        }

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        public double getMaxBudgetUsd() {
            return maxBudgetUsd;
        }

        public void setMaxBudgetUsd(double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Agent getAgent() {
        return agent;
    }

    public List<String> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<String> credentials) {
        this.credentials = credentials;
    }

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow;
    }

    public boolean isUnrestrictedNetwork() {
        return unrestrictedNetwork;
    }

    public void setUnrestrictedNetwork(boolean unrestrictedNetwork) {
        this.unrestrictedNetwork = unrestrictedNetwork;
    }

    public double getCpus() {
        return cpus;
    }

    public void setCpus(double cpus) {
        this.cpus = cpus;
    }

    public DataSize getMemory() {
        return memory;
    }

    public void setMemory(DataSize memory) {
        this.memory = memory;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Map<String, String> getGates() {
        return gates;
    }

    public void setGates(Map<String, String> gates) {
        this.gates = gates;
    }

    public String getReviewerUrl() {
        return reviewerUrl;
    }

    public void setReviewerUrl(String reviewerUrl) {
        this.reviewerUrl = reviewerUrl;
    }

    public boolean isRetainOnConflict() {
        return retainOnConflict;
    }

    public void setRetainOnConflict(boolean retainOnConflict) {
        this.retainOnConflict = retainOnConflict;
    }

    public List<String> getProtectedBranches() {
        return protectedBranches;
    }

    public void setProtectedBranches(List<String> protectedBranches) {
        this.protectedBranches = protectedBranches;
    }
}
