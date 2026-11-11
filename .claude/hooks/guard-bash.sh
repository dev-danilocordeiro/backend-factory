#!/usr/bin/env bash
# PreToolUse guard for Bash. Defense in depth: headless stages already run with
# an allowlist and --permission-prompts none; this hook denies the dangerous
# commands even if an allowlist is misconfigured.
set -euo pipefail
cmd=$(jq -r '.tool_input.command // ""')

deny() {
  jq -n --arg r "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}

# Always, for any session in this repo: no merging and no force pushes.
if grep -Eq '(^|[;&|[:space:]])gh[[:space:]]+pr[[:space:]]+merge' <<<"$cmd"; then
  deny "backend-factory never merges pull requests; a human does."
fi
if grep -Eq 'git[[:space:]]+push.*(--force|-f([[:space:]]|$)|\+)' <<<"$cmd"; then
  deny "Force pushes are not allowed in this repository."
fi

# Inside a factory stage: no git writes, no GitHub, no network tools.
if [[ -n "${FACTORY_STAGE:-}" ]]; then
  if grep -Eq '(^|[;&|[:space:]])git[[:space:]]+(push|commit|checkout|switch|reset|rebase|merge|branch|tag|remote|worktree|stash|clean|restore|config)' <<<"$cmd"; then
    deny "Stage $FACTORY_STAGE may not change git state; the orchestrator owns commits and branches."
  fi
  if grep -Eq '(^|[;&|[:space:]])(gh|curl|wget|ssh|scp|nc|docker|sudo)([[:space:]]|$)' <<<"$cmd"; then
    deny "Stage $FACTORY_STAGE has no network or privileged tools."
  fi
fi
exit 0
