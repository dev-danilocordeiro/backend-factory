#!/usr/bin/env bash
# Deterministic gates for a Gradle service. Usage: gates/java-gradle.sh <build|lint|arch|test> <service-dir>
# NOTE: not exercised by the examples in this repo (see README, Limits).
set -euo pipefail
gate=${1:?gate}; dir=${2:?service dir}
cd "$dir"
gradle=(./gradlew -q --no-daemon)
[ -x ./gradlew ] || gradle=(gradle -q --no-daemon)
case "$gate" in
  build) "${gradle[@]}" testClasses ;;
  lint)  "${gradle[@]}" checkstyleMain checkstyleTest ;;
  arch)  "${gradle[@]}" test --tests '*ArchitectureTest' ;;
  test)  "${gradle[@]}" test ;;
  *) echo "unknown gate: $gate" >&2; exit 64 ;;
esac
