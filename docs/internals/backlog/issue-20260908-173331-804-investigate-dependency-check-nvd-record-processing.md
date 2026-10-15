---
title: "Investigate Dependency-Check NVD record processing failure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: [":platform", ":enforcer", ":profiler"]
target_files:
  - ".github/workflows/ci.yml"
target_symbols:
  - "Run Security Check"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate Dependency-Check NVD record processing failure

**Context:**
CI run 34255417287 attempt 1 logged DatabaseException while processing CVE-2026-6785 because an NVD reference URL exceeded the plugin database column length; the scan still succeeded, but the affected record may be omitted.

**Needed:**
1. Reproduce with the pinned Dependency-Check plugin and determine whether upgrading or a documented suppression preserves complete NVD ingestion.

## Reconfirmed 2026-10-15

CI38814643561 atb6235c38 with the unchanged10.0.4plugin ingests a cold408708-record
update and returns success, but logs URL-column failures for both CVE-2026-6785
(1585-character reference) and CVE-2026-6786 (1115-character reference) against
the1000-character column (`run-6efcfa27c113`). The successful exit therefore does
not prove complete ingestion. This remains independently open; no CVE suppression,
plugin/database patch or reduced scan threshold was introduced by the cleanup.

---
<!-- id: issue-20260908-173331-804-investigate-dependency-check-nvd-record-processing  file: issue-20260908-173331-804-investigate-dependency-check-nvd-record-processing.md -->
