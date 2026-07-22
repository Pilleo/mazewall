---
title: "CI Podman Container Caching Missing"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "unknown"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 159
---

# 🔴 [Severity: LOW]: CI Podman Container Caching Missing

**Context:**
**Hypothesis:** The `mazewall-test-runner` Podman image is built synchronously on every CI pipeline run without leveraging external layer caching (e.g., `--cache-from`), causing unnecessary delays.

In `scripts/run_containerized_tests.sh`, the command `podman build -t mazewall-test-runner -f infra/dev/Containerfile .` executes without any cache-related flags. In GitHub Actions, since the runner environment is ephemeral, this forces a full re-download of packages and JDK distributions defined in `Containerfile` on every single PR and push.


**Needed:**
1. Implement Podman/Buildah layer caching in GitHub Actions. Alternatively, use GitHub Container Registry (GHCR) to push/pull a baseline test runner image or use actions like `docker/setup-buildx-action` (if switching back to Docker) to cache intermediate layers effectively.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``scripts/run_containerized_tests.sh` and `.github/workflows/ci.yml``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
