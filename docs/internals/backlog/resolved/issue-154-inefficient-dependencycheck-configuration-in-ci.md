---
title: "Inefficient DependencyCheck Configuration in CI"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: e46dbfbc-eaed-4a70-a958-aa340879afc3
paperclip_identifier: MAZ-330
---

# ✅ [RESOLVED]: Inefficient DependencyCheck Configuration in CI

*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `.github/workflows/ci.yml`
*   **Context & Proof:** The OWASP Dependency-Check plugin is executed without configuration caching, significantly increasing configuration phase time.
*   **Fix:** Removed the `--no-configuration-cache` flag from the `dependencyCheckAnalyze` step in `ci.yml` now that version `10.0.4` fully supports the configuration cache.
