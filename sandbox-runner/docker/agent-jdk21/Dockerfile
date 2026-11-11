# Reference agent image: JDK 21, Maven, git and a pinned Claude Code CLI.
# The sandbox runs it as the host user with a read-only root file system;
# HOME (/home/sandbox) and /tmp are tmpfs, so nothing here needs to be writable.
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends git maven nodejs npm ca-certificates \
 && npm install -g @anthropic-ai/claude-code@2.1.322 \
 && npm cache clean --force \
 && apt-get purge -y npm && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*
# Claude Code: no telemetry or other non-essential traffic (the allowlist would block it anyway).
ENV CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    DISABLE_AUTOUPDATER=1
