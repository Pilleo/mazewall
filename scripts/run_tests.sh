#!/bin/bash
# Helper script to run tests inside the configured container environment.
# Podman is preferred; run_containerized_tests.sh falls back to Docker.

# Ensure we are in the project root
cd "$(dirname "$0")/.." || exit

echo "🚀 Starting containerized integration tests..."
# We use --info to ensure we see the security violation logs and seccomp transitions
./scripts/run_containerized_tests.sh test integrationTest integrationTestFreshJvm --info "$@"

# Tip: you can pass gradle arguments like: ./scripts/run_tests.sh --tests "*.PolicyTest"
