---
title: "Investigate Dependency-Check NVD record processing failure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
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

---
<!-- id: issue-20260908-173331-804-investigate-dependency-check-nvd-record-processing  file: issue-20260908-173331-804-investigate-dependency-check-nvd-record-processing.md -->
