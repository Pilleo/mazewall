---
title: "CI Podman Container Caching Missing"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "unknown"
effort: "small"
---

# 🔴 [Severity: LOW]: CI Podman Container Caching Missing

*   **Target Area:** `scripts/run_containerized_tests.sh` and `.github/workflows/ci.yml`
*   **Hypothesis:** The `mazewall-test-runner` Podman image is built synchronously on every CI pipeline run without leveraging external layer caching (e.g., `--cache-from`), causing unnecessary delays.
*   **Context & Proof:** In `scripts/run_containerized_tests.sh`, the command `podman build -t mazewall-test-runner -f infra/dev/Containerfile .` executes without any cache-related flags. In GitHub Actions, since the runner environment is ephemeral, this forces a full re-download of packages and JDK distributions defined in `Containerfile` on every single PR and push.
*   **Recommendation:** Implement Podman/Buildah layer caching in GitHub Actions. Alternatively, use GitHub Container Registry (GHCR) to push/pull a baseline test runner image or use actions like `docker/setup-buildx-action` (if switching back to Docker) to cache intermediate layers effectively.
