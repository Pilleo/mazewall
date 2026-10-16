---
title: "Correct secure exec cleanup of injected tracee descriptors"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorReplyInterpreter.kt"
target_symbols:
  - "SupervisorReplyInterpreter"
open_questions: false

paperclip_issue_id: "11f76edc-b7fb-4b7a-b544-1c2cc4492407"
paperclip_identifier: "MAZ-1466"
---

# 🟡 [Severity: MEDIUM]: Correct secure exec cleanup of injected tracee descriptors

**Context:**
Task 8 extraction confirmed that the formerly resolved issue-20260821-113005 only imports and closes a local pidfd_getfd duplicate after exec rewrite denial; the injected tracee descriptor remains until tracee teardown. Preserve the unchanged wire protocol in Task 8 and design actual remote cleanup separately.

**Needed:**

1. Reproduce descriptor growth in a fresh tracee across denied secure exec attempts and specify a real remote cleanup mechanism without inventing local ownership of the remote integer.

## Resolution (2026-09-14)

The JVM peer currently cannot rewrite the blocked tracee registers and always denies the rewrite. The daemon now sends the framed denied rewrite request without injecting a tracee descriptor first, so the denied path has no remote descriptor to leak.

---
<!-- id: issue-20260909-074418-909-correct-secure-exec-cleanup-of-injected-tracee-des  file: issue-20260909-074418-909-correct-secure-exec-cleanup-of-injected-tracee-des.md -->
