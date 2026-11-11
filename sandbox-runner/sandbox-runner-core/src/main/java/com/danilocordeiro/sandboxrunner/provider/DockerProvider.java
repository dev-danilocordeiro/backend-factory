package com.danilocordeiro.sandboxrunner.provider;

import com.danilocordeiro.sandboxrunner.NetworkPolicy;
import com.danilocordeiro.sandboxrunner.Secret;
import com.danilocordeiro.sandboxrunner.internal.Proc;
import com.danilocordeiro.sandboxrunner.spi.ExecResult;
import com.danilocordeiro.sandboxrunner.spi.Mount;
import com.danilocordeiro.sandboxrunner.spi.SandboxDiedException;
import com.danilocordeiro.sandboxrunner.spi.SandboxException;
import com.danilocordeiro.sandboxrunner.spi.SandboxInstance;
import com.danilocordeiro.sandboxrunner.spi.SandboxProvider;
import com.danilocordeiro.sandboxrunner.spi.SandboxSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sandboxes as containers, through the {@code docker} CLI (or {@code podman},
 * which accepts the same flags). Every container runs with all capabilities
 * dropped, no-new-privileges, the host user's uid, a read-only root file
 * system (writable /tmp and $HOME on tmpfs), CPU, memory (no swap) and PID
 * limits, and {@code --network none} unless the policy says otherwise. Its
 * main process is a {@code sleep} that ends shortly after the run's time
 * limit, so a container outlives a crashed JVM by minutes at most.
 * Credentials are passed through the CLI's environment, never on its command line.
 */
public final class DockerProvider implements SandboxProvider {

    static final String LABEL = "sandbox-runner.managed";
    static final String RUN_LABEL = "sandbox-runner.run";
    static final String EXPIRES_LABEL = "sandbox-runner.expires";
    private static final Duration CLI_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration GRACE = Duration.ofMinutes(5);

    private final String binary;
    private final String image;
    private final boolean readOnlyRoot;
    private final String user;

    private DockerProvider(String binary, String image, boolean readOnlyRoot, String user) {
        this.binary = binary;
        this.image = image;
        this.readOnlyRoot = readOnlyRoot;
        this.user = user;
    }

    /** Docker with the given agent image. Pin it by digest in production. */
    public static DockerProvider image(String image) {
        return new DockerProvider("docker", image, true, hostUser());
    }

    /** Podman with the given agent image. */
    public static DockerProvider podman(String image) {
        return new DockerProvider("podman", image, true, hostUser());
    }

    /** Allow writes to the container's root file system (some toolchains insist). */
    public DockerProvider writableRoot() {
        return new DockerProvider(binary, image, false, user);
    }

    @Override
    public String name() {
        return binary;
    }

    static String hostUser() {
        try {
            Path home = Path.of(System.getProperty("user.home"));
            return Files.getAttribute(home, "unix:uid") + ":" + Files.getAttribute(home, "unix:gid");
        } catch (IOException | UnsupportedOperationException e) {
            return "65534:65534";
        }
    }

    private Proc.Result cli(Map<String, String> env, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.addAll(List.of(args));
        return Proc.run(cmd, null, env, CLI_TIMEOUT);
    }

    private String cliOk(String... args) {
        Proc.Result r = cli(Map.of(), args);
        if (!r.ok()) {
            throw new SandboxException(binary + " " + args[0] + " failed: " + r.output().strip());
        }
        return r.stdout().strip();
    }

    @Override
    public SandboxInstance create(SandboxSpec spec) {
        String name = "sandbox-runner-" + spec.runId();
        long expires = Instant.now().plus(spec.limits().timeout()).plus(GRACE).getEpochSecond();
        Container c = new Container(name);
        try {
            List<String> args = new ArrayList<>(List.of("run", "-d", "--init", "--name", name,
                    "--label", LABEL + "=true", "--label", RUN_LABEL + "=" + spec.runId(),
                    "--label", EXPIRES_LABEL + "=" + expires,
                    "--cpus", String.format(Locale.ROOT, "%.2f", spec.limits().cpus()),
                    "--memory", spec.limits().memory().toDockerString(),
                    "--memory-swap", spec.limits().memory().toDockerString(),
                    "--pids-limit", Integer.toString(spec.limits().pids()),
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--user", user, "-e", "HOME=/home/sandbox", "-w", spec.workdir()));
            if (readOnlyRoot) {
                args.addAll(List.of("--read-only", "--tmpfs", "/tmp:rw,exec,size=1g", "--tmpfs", "/home/sandbox:rw,exec,size=2g,uid="
                        + user.split(":")[0] + ",gid=" + user.split(":")[1]));
            }
            args.addAll(network(spec, c));
            for (Mount m : spec.mounts()) {
                args.addAll(List.of("--mount", "type=bind,source=" + m.hostPath() + ",target=" + m.sandboxPath()
                        + (m.readOnly() ? ",readonly" : "")));
            }
            spec.env().forEach((k, v) -> args.addAll(List.of("-e", k + "=" + v)));
            Map<String, String> cliEnv = new HashMap<>();
            for (Secret s : spec.secrets()) {
                args.addAll(List.of("-e", s.name())); // value comes from the CLI's environment
                cliEnv.put(s.name(), s.reveal());
            }
            args.add(image);
            args.addAll(List.of("sleep", Long.toString(spec.limits().timeout().plus(GRACE).toSeconds())));
            Proc.Result r = cli(cliEnv, args.toArray(String[]::new));
            if (!r.ok()) {
                throw new SandboxException(binary + " run failed: " + r.output().strip());
            }
            c.started = true;
            return c;
        } catch (RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private List<String> network(SandboxSpec spec, Container c) {
        NetworkPolicy p = spec.network();
        switch (p.mode()) {
            case NONE:
                return List.of("--network", "none");
            case UNRESTRICTED:
                return List.of();
            default:
                break;
        }
        ensureProxyImage();
        String net = c.name + "-net";
        cliOk("network", "create", "--internal", "--label", LABEL + "=true", "--label", RUN_LABEL + "=" + spec.runId(), net);
        c.network = net;
        String subnet = cliOk("network", "inspect", "-f", "{{(index .IPAM.Config 0).Subnet}}", net);
        try {
            c.proxyDir = Files.createTempDirectory("sandbox-runner-egress-");
            Files.writeString(c.proxyDir.resolve("tinyproxy.conf"), EgressProxy.config(subnet));
            Files.writeString(c.proxyDir.resolve("filter"), EgressProxy.filter(p.allowedDomains()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String proxy = c.name + "-egress";
        cliOk("run", "-d", "--name", proxy, "--label", LABEL + "=true", "--label", RUN_LABEL + "=" + spec.runId(),
                "--label", EXPIRES_LABEL + "=" + Instant.now().plus(spec.limits().timeout()).plus(GRACE).getEpochSecond(),
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only", "--tmpfs", "/run/tinyproxy:rw,uid=65534",
                "--memory", "128m", "--pids-limit", "64",
                "--mount", "type=bind,source=" + c.proxyDir.resolve("tinyproxy.conf") + ",target=/etc/tinyproxy/tinyproxy.conf,readonly",
                "--mount", "type=bind,source=" + c.proxyDir.resolve("filter") + ",target=/etc/tinyproxy/filter,readonly",
                EgressProxy.IMAGE);
        c.proxy = proxy;
        cliOk("network", "connect", "--alias", EgressProxy.ALIAS, net, proxy);
        String url = "http://" + EgressProxy.ALIAS + ":" + EgressProxy.PORT;
        return List.of("--network", net, "-e", "HTTPS_PROXY=" + url, "-e", "HTTP_PROXY=" + url,
                "-e", "https_proxy=" + url, "-e", "http_proxy=" + url, "-e", "NO_PROXY=localhost,127.0.0.1",
                "-e", "no_proxy=localhost,127.0.0.1");
    }

    private void ensureProxyImage() {
        if (cli(Map.of(), "image", "inspect", EgressProxy.IMAGE).ok()) {
            return;
        }
        try {
            Path ctx = EgressProxy.dockerContext(Files.createTempDirectory("sandbox-runner-egress-build-"));
            Proc.Result r = Proc.run(List.of(binary, "build", "-q", "-t", EgressProxy.IMAGE, ctx.toString()), null, Map.of(),
                    Duration.ofMinutes(10));
            if (!r.ok()) {
                throw new SandboxException("building " + EgressProxy.IMAGE + " failed: " + r.output().strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int reapOrphans() {
        long now = Instant.now().getEpochSecond();
        int removed = 0;
        String list = cli(Map.of(), "ps", "-a", "--filter", "label=" + LABEL + "=true",
                "--format", "{{.Names}}\t{{.Label \"" + EXPIRES_LABEL + "\"}}").stdout();
        for (String line : list.split("\n")) {
            String[] f = line.split("\t");
            if (f.length == 2 && !f[1].isBlank() && Long.parseLong(f[1].strip()) < now) {
                if (cli(Map.of(), "rm", "-f", f[0]).ok()) {
                    removed++;
                }
            }
        }
        String nets = cli(Map.of(), "network", "ls", "--filter", "label=" + LABEL + "=true", "--format", "{{.Name}}").stdout();
        for (String n : nets.split("\n")) {
            if (!n.isBlank() && cli(Map.of(), "network", "rm", n.strip()).ok()) {
                removed++; // fails while still in use, which is what we want
            }
        }
        return removed;
    }

    /** One container plus its network resources. */
    private final class Container implements SandboxInstance {
        private final String name;
        private volatile boolean started;
        private volatile boolean closed;
        private String network;
        private String proxy;
        private Path proxyDir;

        Container(String name) {
            this.name = name;
        }

        @Override
        public ExecResult exec(List<String> command, String workdir, Map<String, String> env, Duration timeout) {
            if (closed || !isAlive()) {
                throw new SandboxDiedException("container " + name + " is not running");
            }
            List<String> args = new ArrayList<>(List.of(binary, "exec", "-w", workdir));
            env.forEach((k, v) -> args.addAll(List.of("-e", k + "=" + v)));
            args.add(name);
            args.addAll(command);
            Proc.Result r = Proc.run(args, null, Map.of(), timeout);
            if (r.timedOut()) {
                // Killing the CLI does not stop the process in the container.
                kill();
                return new ExecResult(-1, r.stdout(), r.stderr(), true);
            }
            if (!r.ok() && !isAlive()) {
                throw new SandboxDiedException("container " + name + " stopped during the command (exit " + r.exitCode() + ")");
            }
            return new ExecResult(r.exitCode(), r.stdout(), r.stderr(), false);
        }

        @Override
        public boolean isAlive() {
            if (!started || closed) {
                return false;
            }
            Proc.Result r = cli(Map.of(), "inspect", "-f", "{{.State.Running}}", name);
            return r.ok() && r.stdout().strip().equals("true");
        }

        @Override
        public void kill() {
            cli(Map.of(), "kill", name);
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            cli(Map.of(), "rm", "-f", "-v", name);
            if (proxy != null) {
                cli(Map.of(), "rm", "-f", proxy);
            }
            if (network != null) {
                cli(Map.of(), "network", "rm", network);
            }
            if (proxyDir != null) {
                try (var s = Files.list(proxyDir)) {
                    for (Path p : s.toList()) {
                        Files.deleteIfExists(p);
                    }
                    Files.deleteIfExists(proxyDir);
                } catch (IOException ignored) {
                    // temp files; best effort
                }
            }
        }
    }
}
