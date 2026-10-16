# Development Infrastructure

This directory contains the configuration for the `mazewall` development and testing environment.

---

## Dev Container Environment

The most reliable way to develop and test `mazewall` is using the provided **Podman** dev container. It is configured to handle the specific kernel requirements and security permissions needed for nested seccomp/Landlock testing.

### Prerequisites
- **Podman** (Rootless recommended)
- **Podman Compose**

### Starting the Environment

```bash
# From the project root
export MAZEWALL_UID="$(id -u)" MAZEWALL_GID="$(id -g)"
export MAZEWALL_CONTAINER_CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/mazewall/container-gradle/dev-$(id -u)"
mkdir -p "$MAZEWALL_CONTAINER_CACHE_DIR"
podman compose -f infra/dev/compose.yml up -d

# Enter the container
podman compose -f infra/dev/compose.yml exec mazewall bash
```

The dev container and Compose setup use Podman `keep-id`, the caller UID/GID,
and a separate caller-owned Gradle cache. Export the same three variables
before opening the VS Code devcontainer. This rootless Podman configuration
must not be run through rootful Docker, whose user namespace does not support
`keep-id`; use `scripts/run_containerized_tests.sh` for local rootful Docker.
Build containers must never mount the host's ordinary `~/.gradle` writable.
The runner verifies UID mapping with an owned-file probe before allowing a
writable checkout bind; if the endpoint is remote or mapping is uncertain it
fails before any build writes occur. Privileged Tier E gates stage source into
engine-managed volumes and export artifacts with the client Docker command,
leaving the checkout and host Gradle cache untouched even if the container
fails. Prepared dependency caches persist between runs; only per-run workspaces
are retired.

Short Tier E endpoint tests are ordinary JUnit integration tests:

```bash
# Explicit rootful Docker server, when available:
TIER_E_CONTAINER_ENGINE=docker MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock \
  ./gradlew :profiler:rootfulContainerTest

# Or let the condition inspect the currently selected Docker/Podman endpoint:
./gradlew :profiler:integrationTest
```

The rootful task re-evaluates availability on every invocation, including
when the same Gradle daemon previously saw a different endpoint. A missing
or rootless endpoint records a JUnit skip with a reason; a fixture failure
after admission fails. The 15-fork 75,000-operation benchmark is **only**
run by explicitly invoking `:profiler:tierELongMatchedBenchmark` after
preparing the native Release bundle and image. The specified 800 µs pacing
alone imposes a 60-second wall-clock floor per arm; cache reuse cannot
remove that measurement work.
The short split-topology fixture requires a proven local `unix://` endpoint;
it resolves the selected Docker context or Podman default connection, proves
local PID namespace and bind-mount visibility, then verifies the registered
container-host PID via local `/proc`. Remote rootful endpoints may still run
the mount-free engine and ownership tests; the split fixture skips with a
distinct reason rather than using the wrong host PID. The five short JUnit
cases passed on a local rootful Docker server using its default context.
Podman is supported by the engine adapter but its runtime behavior remains
unverified on machines without a reachable rootful Podman server.

---

## Nested Sandboxing & Seccomp Profiles

`mazewall` tests require the ability to "stack" seccomp filters (installing a filter inside a container that already has a filter). By default, many container runtimes block this.

We use a custom [podman-seccomp.json](podman-seccomp.json) profile that whitelists `seccomp(2)` and `landlock_create_ruleset(2)`. This profile is automatically applied via the `io.podman.annotations.seccomp` annotation in the `compose.yml`.

> [!IMPORTANT]
> **Rootless Podman:** The configuration is optimized for rootless Podman. If using Docker, you may need to run with `--privileged` or manually provide the `podman-seccomp.json` as a security-opt.

---

## Running Tests

Once inside the container, you can run the full test suite without worrying about your host kernel's specific configuration (as long as the host kernel is 6.2+).

```bash
# Run all integration tests
./gradlew test

# Run a specific module's check
./gradlew :enforcer:check
```

---

## Troubleshooting

### "ENAMETOOLONG" Error
If you see an `ENAMETOOLONG` error when starting the container, it is likely because your orchestrator is trying to pass the entire seccomp JSON profile as a string. Our `compose.yml` uses the Podman-native annotation path to avoid this issue.

### Kernel Feature Detection
`mazewall` automatically detects missing kernel features. If you are running on an older kernel, integration tests that require Landlock or `io_uring` will be skipped or reported as such. For the full test experience, ensure your host kernel is **6.2+**.
