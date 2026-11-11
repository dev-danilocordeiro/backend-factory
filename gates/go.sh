#!/usr/bin/env bash
# Deterministic gates for a Go service. Usage: gates/go.sh <build|lint|arch|test> <service-dir>
set -euo pipefail
gate=${1:?gate}; dir=${2:?service dir}
cd "$dir"
case "$gate" in
  build) go build ./... && go vet ./... ;;
  lint)  golangci-lint run ./... ;;
  # Layer rules live in .golangci.yml (depguard); run only that linter so the report is unambiguous.
  arch)  golangci-lint run --enable-only depguard ./... ;;
  test)  go test -count=1 ./... ;;
  *) echo "unknown gate: $gate" >&2; exit 64 ;;
esac
