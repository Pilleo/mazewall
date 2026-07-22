#!/usr/bin/env bash
set -e

# This script replaces the ContainerizedTestRunner.kt for CI environments
# where Testcontainers might be unstable or resource-constrained.

PROJECT_ROOT=$(pwd)
SECCOMP_PROFILE="${PROJECT_ROOT}/infra/dev/podman-seccomp.json"
CONTAINER_NAME="mazewall-integration-tests"

if ! podman image exists mazewall-test-runner && ! podman image exists localhost/mazewall-test-runner; then
    echo "Building test runner image..."
    podman build --pull=missing --cache-from=mazewall-test-runner -t mazewall-test-runner -f infra/dev/Containerfile .
else
    echo "Using cached test runner image."
fi

echo "Starting integration tests in Podman..."
PODMAN_ARGS=(
    --name "${CONTAINER_NAME}"
    --network host
    --userns host
    --security-opt "seccomp=${SECCOMP_PROFILE}"
    --cap-add AUDIT_READ
    --cap-add AUDIT_CONTROL
    --cap-add SYS_ADMIN
    --cap-add SYS_PTRACE
    -v "${PROJECT_ROOT}:${PROJECT_ROOT}"
    -v "${HOME}/.gradle:${HOME}/.gradle"
    -e GRADLE_USER_HOME="${HOME}/.gradle"
    -e IO_MAZEWALL_TEST=true
    -e MAZEWALL_IN_CONTAINER=true
    -e GITHUB_ACTIONS="${GITHUB_ACTIONS:-false}"
    -e LANG=C.UTF-8
    -e LC_ALL=C.UTF-8
    -e NVD_API_KEY
    -e GITHUB_TOKEN
    -e GITHUB_ACTOR
    -w "${PROJECT_ROOT}"
)

if [ "${GITHUB_ACTIONS:-false}" == "true" ] && [ -n "${RUNNER_TEMP}" ]; then
    PODMAN_ARGS+=(
        -v "${RUNNER_TEMP}:${RUNNER_TEMP}"
        -e RUNNER_TEMP
        -e GITHUB_STEP_SUMMARY
        -e GITHUB_STATE
        -e GITHUB_OUTPUT
        -e GITHUB_ENV
        -e GITHUB_WORKSPACE
    )
fi

podman run --rm "${PODMAN_ARGS[@]}" mazewall-test-runner ./gradlew "$@" --no-daemon --stacktrace
