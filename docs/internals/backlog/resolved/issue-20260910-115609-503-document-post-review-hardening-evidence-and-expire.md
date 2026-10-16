---
title: "Document post-review hardening evidence and expired notification regression"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt"
target_symbols:
  - "SeccompSessionHandler"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Document post-review hardening evidence and expired notification regression

**Context:**
Post-review follow-up: reviewers need a 30-finding evidence manifest and a direct session-level ENOENT regression beyond the shared reply classifier.

**Needed:**
1. Add a direct pass-through session test for an expired USER_NOTIF reply, then record each completed hardening finding with its commit and regression evidence.

**Resolution (2026-09-10):** `SeccompSessionHandlerTest` now drives a pass-through
notification to an `ENOENT` reply and proves the session terminates without another receive or
reply. The canonical [post-review hardening evidence](../../designs/core/post-review-hardening-evidence.md)
maps all 30 accepted findings to their implementation commit, executable regression, and module
gate.

---
<!-- id: issue-20260910-115609-503-document-post-review-hardening-evidence-and-expire  file: issue-20260910-115609-503-document-post-review-hardening-evidence-and-expire.md -->
