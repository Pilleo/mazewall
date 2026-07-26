---
title: "Redundant JaCoCo Test Coverage Verification in CI"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Redundant JaCoCo Test Coverage Verification in CI

**Status:** RESOLVED (July 2026)
*   **Target Area:** `.github/workflows/ci.yml`
*   **Context & Proof:** The CI workflow previously executed the Jacoco verification phase separately, using `-x` to skip it and executing it later.
*   **Fix:** By running the entire build inside the container, both unit and integration tests run in the same Gradle invocation, allowing JaCoCo to automatically aggregate execution data and verify thresholds as part of the standard `check` phase.
