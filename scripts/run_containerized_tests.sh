#!/usr/bin/env bash
set -e

# This script replaces the ContainerizedTestRunner.kt for CI environments
# where Testcontainers might be unstable or resource-constrained. Podman is
# preferred, but Docker is supported when it is the available container
# engine.

PROJECT_ROOT=$(pwd)
SECCOMP_PROFILE="${PROJECT_ROOT}/infra/dev/podman-seccomp.json"
CONTAINER_NAME="mazewall-integration-tests"

select_engine() {
    local requested="${MAZEWALL_CONTAINER_ENGINE:-}"
    if [[ -n "$requested" ]]; then
        case "$requested" in
            podman|docker) CONTAINER_ENGINE="$requested"; return 0 ;;
            *) echo "Unsupported MAZEWALL_CONTAINER_ENGINE: $requested (expected podman or docker)" >&2; return 1 ;;
        esac
    fi

    if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
        CONTAINER_ENGINE=podman
        return 0
    fi

    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        CONTAINER_ENGINE=docker
        return 0
    fi

    if command -v docker >/dev/null 2>&1 && [[ -S /var/run/docker.sock ]]; then
        DOCKER_HOST="unix:///var/run/docker.sock"
        export DOCKER_HOST
        if docker info >/dev/null 2>&1; then
            CONTAINER_ENGINE=docker
            return 0
        fi
    fi

    echo "No usable Podman or Docker engine was found." >&2
    return 1
}

CONTAINER_ENGINE=""
select_engine
echo "Using ${CONTAINER_ENGINE} for containerized integration tests."

if [[ "$CONTAINER_ENGINE" == podman ]]; then
    if ! podman image exists mazewall-test-runner && ! podman image exists localhost/mazewall-test-runner; then
        echo "Building test runner image..."
        podman build --pull=missing --cache-from=mazewall-test-runner -t mazewall-test-runner -f infra/dev/Containerfile .
    else
        echo "Using cached test runner image."
    fi
else
    if ! docker image inspect mazewall-test-runner >/dev/null 2>&1; then
        echo "Building test runner image..."
        docker build -t mazewall-test-runner -f infra/dev/Containerfile .
    else
        echo "Using cached test runner image."
    fi
fi

echo "Starting integration tests in ${CONTAINER_ENGINE}..."
CONTAINER_ARGS=(
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
    if [[ "$CONTAINER_ENGINE" == podman ]]; then
        CONTAINER_ARGS+=(--runtime runc)
    fi
fi

if [ "${GITHUB_ACTIONS:-false}" == "true" ] && [ -n "${RUNNER_TEMP}" ]; then
    CONTAINER_ARGS+=(
        -v "${RUNNER_TEMP}:${RUNNER_TEMP}"
        -e RUNNER_TEMP
        -e GITHUB_STEP_SUMMARY
        -e GITHUB_STATE
        -e GITHUB_OUTPUT
        -e GITHUB_ENV
        -e GITHUB_WORKSPACE
    )
fi

# Pass caller arguments straight through to Gradle. Module-inclusion policy
# belongs to settings.gradle.kts: if a task targets an excluded project,
# Gradle must fail loudly — never silently skip (fail-closed posture).
if [[ "$CONTAINER_ENGINE" == podman ]]; then
    podman run --rm --replace "${CONTAINER_ARGS[@]}" mazewall-test-runner ./gradlew "$@" --no-daemon --stacktrace
else
    existing_container=$(docker ps -aq --filter "name=^/${CONTAINER_NAME}$")
    if [[ -n "$existing_container" ]]; then
        docker rm -f "$CONTAINER_NAME" >/dev/null
    fi
    docker run --rm "${CONTAINER_ARGS[@]}" mazewall-test-runner ./gradlew "$@" --no-daemon --stacktrace
fi
