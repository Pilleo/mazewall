---
title: "Gradle Configuration Cache Path Collision in CI"
severity: "MEDIUM"
status: "open"
---

# 🟡 [Severity: MEDIUM]: Gradle Configuration Cache Path Collision in CI
**Context:**
In the GitHub Actions CI workflow (`ci.yml`), Gradle is executed in two different environments that share the same Gradle User Home (`~/.gradle`):
1. **Inside the Podman container:** via `./scripts/run_containerized_tests.sh build` (workspace root is `/workspace`).
2. **On the host runner:** via `./gradlew :enforcer:pitest`, `./gradlew dependencyCheckAnalyze`, and `./gradlew publish...` (workspace root is `/home/runner/work/mazewall/mazewall`).

Because both environments share the same `GRADLE_USER_HOME` cache but have different project root paths, the Gradle configuration cache stores and attempts to reuse absolute paths that do not exist or are mismatched between the host and the container. This causes configuration cache corruption, invalidates configuration cache benefits, and can lead to build failures or stale configurations being loaded from sibling PR caches.

**Needed:**
Isolate the host Gradle execution from the container Gradle execution, or disable Gradle configuration caching (`org.gradle.configuration-cache=false`) in CI runs to ensure clean, correct configurations are always generated for each build.
