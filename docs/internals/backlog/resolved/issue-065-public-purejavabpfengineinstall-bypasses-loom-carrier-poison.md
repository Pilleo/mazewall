---
title: "Public `PureJavaBpfEngine.install` bypasses Loom Carrier Poisoning safeguards and JIT warmups"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Public `PureJavaBpfEngine.install` bypasses Loom Carrier Poisoning safeguards and JIT warmups

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.seccomp.PureJavaBpfEngine` & `io.mazewall.enforcer.ContainedExecutors`
**Context:** The `PureJavaBpfEngine` and `SeccompEngine` were public and lacked check checks for virtual threads, allowing users to call them directly, potentially poisoning carrier threads.
**Fix:** Declared `SeccompEngine` and `PureJavaBpfEngine` as `internal` to prevent direct external access. Added a virtual thread check `if (Thread.currentThread().isVirtual)` inside `PureJavaBpfEngine.installInternal` as a defense-in-depth safety measure.
