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
podman compose -f infra/dev/compose.yml up -d

# Enter the container
podman compose -f infra/dev/compose.yml exec mazewall bash
```

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
