#!/usr/bin/env bash
set -e

# This script replaces the ContainerizedTestRunner.kt for CI environments
# where Testcontainers might be unstable or resource-constrained.

PROJECT_ROOT=$(pwd)
SECCOMP_PROFILE="${PROJECT_ROOT}/infra/dev/podman-seccomp.json"
CONTAINER_NAME="mazewall-integration-tests"

if ! podman image exists mazewall-test-runner; then
    echo "Building test runner image..."
    podman build -t mazewall-test-runner -f infra/dev/Containerfile .
else
    echo "Using cached test runner image."
fi

echo "Starting integration tests in Podman..."
# We use the same flags that were in ContainerizedTestRunner.kt
podman run --rm \
    --name "${CONTAINER_NAME}" \
    --network host \
    --userns host \
    --security-opt "seccomp=${SECCOMP_PROFILE}" \
    --cap-add AUDIT_READ \
    --cap-add AUDIT_CONTROL \
    --cap-add SYS_ADMIN \
    --cap-add SYS_PTRACE \
    -v "${PROJECT_ROOT}:/workspace" \
    -v "${HOME}/.gradle:/root/.gradle" \
    -e GRADLE_USER_HOME=/root/.gradle \
    -e IO_MAZEWALL_TEST=true \
    -e MAZEWALL_IN_CONTAINER=true \
    -e GITHUB_ACTIONS="${GITHUB_ACTIONS:-false}" \
    -e LANG=C.UTF-8 \
    -e LC_ALL=C.UTF-8 \
    -w /workspace \
    mazewall-test-runner \
    ./gradlew "$@" --no-daemon --stacktrace
