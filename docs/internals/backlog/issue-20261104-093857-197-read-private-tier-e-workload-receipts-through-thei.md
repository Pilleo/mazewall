---
title: "Read private Tier E workload receipts through their collector"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "docs/internals/backlog/issue-20261104-093857-197-read-private-tier-e-workload-receipts-through-thei.md"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "exercise"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Read private Tier E workload receipts through their collector

**Context:**
Cycle004 reached READY and ARM, observed 40 committed successful input opens and the ready-file open, and retained attributed-complete from the target, then the host test failed AccessDeniedException reading work/ready. The diagnostic intentionally launches UID20000 with umask077; its ready/done files are private. Existing mandatory export reads them successfully through the owned privileged collector. The three direct host text reads in exercise cross the wrong credential boundary.

**Needed:**
1. Use the existing checked collector exec cat path for all three ready/done content reads, preserving the exact expected text, existence poll, wait order, timeouts, UID/modes and all workload counts.
2. Check integration compilation, profiler ktlint, backlog references and whitespace. Obtain independent review, fresh diagnostic and normal rootful suite/full gate before qualification; the observed runtime failure is the regression evidence, not proof of the fix.

---
<!-- id: issue-20261104-093857-197-read-private-tier-e-workload-receipts-through-thei  file: issue-20261104-093857-197-read-private-tier-e-workload-receipts-through-thei.md -->
