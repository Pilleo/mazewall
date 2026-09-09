---
title: "Correct secure exec cleanup of injected tracee descriptors"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorReplyInterpreter.kt"
target_symbols:
  - "SupervisorReplyInterpreter"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Correct secure exec cleanup of injected tracee descriptors

**Context:**
Task 8 extraction confirmed that the formerly resolved issue-20260821-113005 only imports and closes a local pidfd_getfd duplicate after exec rewrite denial; the injected tracee descriptor remains until tracee teardown. Preserve the unchanged wire protocol in Task 8 and design actual remote cleanup separately.

**Needed:**

1. Reproduce descriptor growth in a fresh tracee across denied secure exec attempts and specify a real remote cleanup mechanism without inventing local ownership of the remote integer.

---
<!-- id: issue-20260909-074418-909-correct-secure-exec-cleanup-of-injected-tracee-des  file: issue-20260909-074418-909-correct-secure-exec-cleanup-of-injected-tracee-des.md -->
