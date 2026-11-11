#!/usr/bin/env bash
# Deterministic gates for a Maven service. Usage: gates/java-maven.sh <build|lint|arch|test> <service-dir>
set -euo pipefail
gate=${1:?gate}; dir=${2:?service dir}
mvn=(mvn -B -q -f "$dir/pom.xml" -Dsurefire.failIfNoSpecifiedTests=false)
case "$gate" in
  build) "${mvn[@]}" test-compile ;;
  lint)  "${mvn[@]}" checkstyle:check ;;
  # ArchUnit rules live in ArchitectureTest; run them alone so the report is unambiguous.
  arch)  "${mvn[@]}" test -Dtest='ArchitectureTest' ;;
  test)  "${mvn[@]}" test -Dtest='!ArchitectureTest' ;;
  *) echo "unknown gate: $gate" >&2; exit 64 ;;
esac
