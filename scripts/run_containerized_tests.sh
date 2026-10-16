#!/usr/bin/env bash
set -euo pipefail

# This script replaces the ContainerizedTestRunner.kt for CI environments
# where Testcontainers might be unstable or resource-constrained. Podman is
# preferred, but Docker is supported when it is the available container
# engine.

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SECCOMP_PROFILE="${PROJECT_ROOT}/infra/dev/podman-seccomp.json"
CONTAINER_NAME="mazewall-integration-tests-$$-$(date +%s)"
CALLER_UID=$(id -u)
CALLER_GID=$(id -g)

# A previous interrupted root build can poison host compilation before the
# current container even starts. Report the exact owned-output blocker rather
# than allowing Gradle to fail later with an opaque cache exception.
for output_dir in "${PROJECT_ROOT}/build" "${PROJECT_ROOT}/buildSrc/build" \
    "${PROJECT_ROOT}/enforcer/build" "${PROJECT_ROOT}/platform/build" \
    "${PROJECT_ROOT}/profiler/build" "${PROJECT_ROOT}/profiler-native/build"; do
    if [[ -d "${output_dir}" ]]; then
        foreign=$(find "${output_dir}" -xdev ! -user "${CALLER_UID}" -print -quit)
        if [[ -n "${foreign}" ]]; then
            echo "Non-caller-owned generated build output blocks safe tests: ${foreign}" >&2
            exit 1
        fi
    fi
done

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
if [[ "${CONTAINER_ENGINE}" == docker && -n "${MAZEWALL_DOCKER_HOST:-}" ]]; then
    DOCKER_HOST="${MAZEWALL_DOCKER_HOST}"
    export DOCKER_HOST
fi

# Only local engine sockets may bind the checkout. Remote engines cannot see
# the client checkout or its UID mapping and must use an isolated staged run.
case "${MAZEWALL_DOCKER_HOST:-${DOCKER_HOST:-${CONTAINER_HOST:-}}}" in
    ""|unix://*) ;;
    *) echo 'Remote container endpoint requires an isolated staged workspace' >&2; exit 2 ;;
esac

# This cache is dedicated to caller-owned container builds. Never mount the
# developer's ordinary ~/.gradle in a container (especially a privileged one).
PROJECT_KEY=$(printf '%s' "${PROJECT_ROOT}" | sha256sum | cut -c1-12)
TOOLCHAIN_KEY=$(sha256sum "${PROJECT_ROOT}/gradle/wrapper/gradle-wrapper.properties" \
    "${PROJECT_ROOT}/infra/dev/Containerfile" | sha256sum | cut -c1-12)
CACHE_HOME="${XDG_CACHE_HOME:-${HOME}/.cache}/mazewall/container-gradle/${CONTAINER_ENGINE}/${CALLER_UID}/${PROJECT_KEY}/${TOOLCHAIN_KEY}"
NVD_CACHE="${XDG_CACHE_HOME:-${HOME}/.cache}/mazewall/nvd-db"
mkdir -p "${CACHE_HOME}/dependency-check-data" "${NVD_CACHE}"
[[ -O "${CACHE_HOME}" && -O "${CACHE_HOME}/dependency-check-data" && -O "${NVD_CACHE}" ]] || {
    echo 'container-specific cache must be caller-owned' >&2; exit 1;
}
foreign=$(find "${CACHE_HOME}" -xdev ! -user "${CALLER_UID}" -print -quit)
[[ -z "${foreign}" ]] || { echo "Non-caller-owned container cache entry: ${foreign}" >&2; exit 1; }

USER_NAMESPACE=host
if [[ "${CONTAINER_ENGINE}" == podman ]]; then
    USER_NAMESPACE=keep-id
fi

CONTAINERFILE_SHA=$(sha256sum "${PROJECT_ROOT}/infra/dev/Containerfile" | cut -d' ' -f1)
if [[ "$CONTAINER_ENGINE" == podman ]]; then
    image_sha=$(podman image inspect mazewall-test-runner --format '{{index .Config.Labels "io.mazewall.containerfile.sha256"}}' 2>/dev/null || true)
    if [[ "$image_sha" != "$CONTAINERFILE_SHA" ]]; then
        echo "Building test runner image..."
        podman build --pull=missing --cache-from=mazewall-test-runner \
            --label "io.mazewall.containerfile.sha256=${CONTAINERFILE_SHA}" \
            -t mazewall-test-runner -f infra/dev/Containerfile .
    else
        echo "Using cached test runner image."
    fi
else
    image_sha=$(docker image inspect mazewall-test-runner --format '{{index .Config.Labels "io.mazewall.containerfile.sha256"}}' 2>/dev/null || true)
    if [[ "$image_sha" != "$CONTAINERFILE_SHA" ]]; then
        echo "Building test runner image..."
        docker build --label "io.mazewall.containerfile.sha256=${CONTAINERFILE_SHA}" \
            -t mazewall-test-runner -f infra/dev/Containerfile .
    else
        echo "Using cached test runner image."
    fi
fi

echo "Starting integration tests in ${CONTAINER_ENGINE}..."
PROBE_DIR=$(mktemp -d)
trap 'rm -rf "${PROBE_DIR}"' EXIT
# A successful daemon connection says nothing about bind-mount UID mapping.
# Reject the writable checkout mount unless the container creates this marker
# as the actual host caller. The probe never writes inside the project.
"${CONTAINER_ENGINE}" run --rm --userns "${USER_NAMESPACE}" --user "${CALLER_UID}:${CALLER_GID}" \
    -v "${PROBE_DIR}:/mazewall-ownership-probe" --entrypoint /bin/sh mazewall-test-runner \
    -c 'printf owned > /mazewall-ownership-probe/owned'
[[ -f "${PROBE_DIR}/owned" && "$(stat -c %u:%g "${PROBE_DIR}/owned")" == "${CALLER_UID}:${CALLER_GID}" ]] || {
    echo 'Container bind-mount identity does not map to the caller; refusing writable checkout mount' >&2
    exit 1
}
CONTAINER_ARGS=(
    --name "${CONTAINER_NAME}"
    --init
    --network host
    --userns "${USER_NAMESPACE}"
    --user "${CALLER_UID}:${CALLER_GID}"
    --security-opt "seccomp=${SECCOMP_PROFILE}"
    --cap-add SYS_ADMIN
    --cap-add SYS_PTRACE
    -v "${PROJECT_ROOT}:${PROJECT_ROOT}"
    -v "${CACHE_HOME}:/container-gradle-home"
    -v "${NVD_CACHE}:/container-gradle-home/dependency-check-data"
    -e GRADLE_USER_HOME=/container-gradle-home
    -e JAVA_TOOL_OPTIONS=-Djava.util.prefs.userRoot=/container-gradle-home/java-preferences
    -e HOME=/tmp
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

if [ "${GITHUB_ACTIONS:-false}" == "true" ] && [ -n "${RUNNER_TEMP:-}" ]; then
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
    docker run --rm "${CONTAINER_ARGS[@]}" mazewall-test-runner ./gradlew "$@" --no-daemon --stacktrace
fi
