---
title: "Authenticate installer context before resolver inspection"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_bootstrap_checkpoint_adapter"
open_questions: false
---

# 🟡 [Severity: HIGH]: Authenticate installer context before resolver inspection

**Context:**
P1: the charged-member resolver unpacks a replaceable context and reads its shared function before authenticating the actual Root installer frame or joining context object identity to its local. Foreign protocols can execute even for an unknown token. Evidence: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/checkpoint-provider-review.md. No live operation/provider qualification is claimed.

**Needed:**
1. Reproduce iterator-context, foreign tuple field and copied active-context cases; authenticate caller first, require exact context/local identity before dereference, preserve identity-only cleanup and prove zero traps, then scoped re-review.

---
<!-- id: issue-20261102-193152-428-authenticate-installer-context-before-resolver-ins  file: issue-20261102-193152-428-authenticate-installer-context-before-resolver-ins.md -->
