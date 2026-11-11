package com.danilocordeiro.sandboxrunner.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** tinyproxy configuration for a domain allowlist. */
final class EgressProxy {

    static final String IMAGE = "sandbox-runner/egress-proxy:0.1";
    static final String ALIAS = "egress";
    static final int PORT = 8888;

    private EgressProxy() {
    }

    /** One anchored extended regex per domain; "*.x.com" means any subdomain of x.com, not x.com itself. */
    static String filter(List<String> domains) {
        StringBuilder sb = new StringBuilder();
        for (String d : domains) {
            if (d.startsWith("*.")) {
                // NetworkPolicy already restricts domains to [a-z0-9.-], so escaping dots is enough.
                sb.append("^([a-z0-9-]+\\.)+").append(d.substring(2).replace(".", "\\.")).append("$\n");
            } else {
                sb.append('^').append(d.replace(".", "\\.")).append("$\n");
            }
        }
        return sb.toString();
    }

    /** Clients are restricted to the sandbox's internal subnet; only CONNECT to 443 is allowed for TLS. */
    static String config(String clientSubnet) {
        return """
                Port %d
                Listen 0.0.0.0
                Timeout 600
                MaxClients 64
                LogLevel Connect
                PidFile "/run/tinyproxy/tinyproxy.pid"
                Allow %s
                DisableViaHeader Yes
                FilterDefaultDeny Yes
                FilterType ere
                FilterURLs No
                FilterCaseSensitive No
                Filter "/etc/tinyproxy/filter"
                ConnectPort 443
                """.formatted(PORT, clientSubnet);
    }

    /** Writes the bundled Dockerfile to dir so the image can be built locally. */
    static Path dockerContext(Path dir) {
        try (InputStream in = EgressProxy.class.getResourceAsStream("/com/danilocordeiro/sandboxrunner/egress/Dockerfile")) {
            if (in == null) {
                throw new IllegalStateException("bundled egress Dockerfile missing");
            }
            Files.createDirectories(dir);
            Files.write(dir.resolve("Dockerfile"), in.readAllBytes());
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
