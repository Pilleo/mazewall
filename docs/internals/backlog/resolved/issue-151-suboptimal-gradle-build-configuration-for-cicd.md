---
title: "Suboptimal Gradle Build Configuration for CI/CD"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 2f6d64f2-e1f9-4d6e-86c8-b9c1c3be85b0
paperclip_identifier: MAZ-327
---

# ✅ [RESOLVED]: Suboptimal Gradle Build Configuration for CI/CD

**Status:** RESOLVED (July 2026)
*   **Target Area:** `.github/workflows/ci.yml` and `build.gradle.kts`
*   **Context & Proof:** The GitHub Actions workflow previously disabled parallel execution and configuration caching for Gradle tasks via `--no-parallel` and `--no-configuration-cache`.
*   **Fix:** Merged the steps into a single containerized step running `./gradlew build` inside the container, leveraging parallel execution and configuration cache natively using standard configuration from `gradle.properties`.
