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
    --cap-add SYS_ADMIN
    --cap-add SYS_PTRACE
    -v "${PROJECT_ROOT}:${PROJECT_ROOT}"
    -v "${HOME}/.gradle:${HOME}/.gradle"
    -e GRADLE_USER_HOME="${HOME}/.gradle"
    -e IO_MAZEWALL_TEST=true
    -e MAZEWALL_IN_CONTAINER=true
    -e QEMU_CPU="${QEMU_CPU:-host}"
    -e CI="${CI:-false}"
    -e GITHUB_ACTIONS="${GITHUB_ACTIONS:-false}"
    -e LANG=C.UTF-8
    -e LC_ALL=C.UTF-8
    -e NVD_API_KEY
    -e GITHUB_TOKEN
    -e GITHUB_ACTOR
    -w "${PROJECT_ROOT}"
)

if command -v runc &> /dev/null; then
    PODMAN_ARGS+=(--runtime runc)
fi

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

# Run gradlew inside container with portal test inclusion check
# This ensures portal integration tests are always run, with graceful degradation
# if the portal module is temporarily excluded from settings.gradle.kts
podman run --rm --replace "${PODMAN_ARGS[@]}" mazewall-test-runner bash -c '
set -e
echo "==> Checking portal module availability..."
PROJECTS_OUTPUT=$(./gradlew projects --no-configuration-cache 2>/dev/null)
if echo "$PROJECTS_OUTPUT" | grep -q "^project \":portal" "; then
  echo "==> Portal module found, including :portal:test :portal:integrationTest"
  ./gradlew :portal:test :portal:integrationTest "$@" --no-daemon --stacktrace
else
  echo "WARNING: Portal module not available in settings.gradle.kts - skipping portal tests"
  echo "         CI proceeding with available modules only."
  echo "         Ensure all modules are included before merging."
  ./gradlew "$@" --no-daemon --stacktrace
fi
'
