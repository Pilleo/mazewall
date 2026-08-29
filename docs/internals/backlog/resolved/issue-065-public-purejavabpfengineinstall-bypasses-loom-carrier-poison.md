---
title: "Public `PureJavaBpfEngine.install` bypasses Loom Carrier Poisoning safeguards and JIT warmups"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 060f1c9d-bf84-47bd-befe-6f47a1a8290b
paperclip_identifier: MAZ-254
---

# ✅ [RESOLVED]: Public `PureJavaBpfEngine.install` bypasses Loom Carrier Poisoning safeguards and JIT warmups

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.seccomp.PureJavaBpfEngine` & `io.mazewall.enforcer.ContainedExecutors`
**Context:** The `PureJavaBpfEngine` and `SeccompEngine` were public and lacked check checks for virtual threads, allowing users to call them directly, potentially poisoning carrier threads.
**Fix:** Declared `SeccompEngine` and `PureJavaBpfEngine` as `internal` to prevent direct external access. Added a virtual thread check `if (Thread.currentThread().isVirtual)` inside `PureJavaBpfEngine.installInternal` as a defense-in-depth safety measure.
