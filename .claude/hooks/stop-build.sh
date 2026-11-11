#!/usr/bin/env bash
# Stop hook. When an implement or test stage tries to finish with a broken
# build, send it back once with the compiler output. This is only early
# feedback: the orchestrator runs every gate again afterwards and decides.
set -euo pipefail
input=$(cat)
case "${FACTORY_STAGE:-}" in implement|test) ;; *) exit 0 ;; esac
# Already continuing because of this hook: let it stop; the gates will catch it.
[[ $(jq -r '.stop_hook_active // false' <<<"$input") == true ]] && exit 0

root=${CLAUDE_PROJECT_DIR:-$(pwd)}
script="$root/gates/${FACTORY_STACK:?}.sh"
if out=$("$script" build "$root/${FACTORY_SERVICE:?}" 2>&1); then
  exit 0
fi
jq -n --arg r "The build is broken; fix it before finishing. Output (tail):
$(tail -c 3000 <<<"$out")" '{decision:"block", reason:$r}'
