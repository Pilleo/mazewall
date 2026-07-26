---
title: "Thread-Safety Violation: Mutable `LongArray` in `SyscallEvent`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Thread-Safety Violation: Mutable `LongArray` in `SyscallEvent`

**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.engine.SyscallEvent`
**Context & Proof:** `SyscallEvent` used `val args: LongArray`. Since arrays in JVM are mutable, any reference holder can execute `event.args[0] = value`.
**Fix:** Refactored `SyscallEvent` to use an immutable `List<Long>` for `args`, ensuring the captured state remains constant.
