---
title: "Deduplicate SyscallInvoker errno capture boilerplate"
severity: "LOW"
status: "resolved"
priority: medium
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/SyscallInvoker.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
target_symbols:
  - "SyscallInvoker"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: false

paperclip_issue_id: "6230c47b-72eb-4d36-909b-685a1a269811"
paperclip_identifier: "MAZ-1187"
---

# 🟢 [Severity: LOW]: Deduplicate SyscallInvoker errno capture boilerplate

**Context:**
Errno capture is centralized in intent but duplicated across a ~390-line `SyscallInvoker` (`invokeExact` then `getErrno` per syscall). `gettid` captures errno and drops it. `RealNativeEngine` is a `@Suppress("TooManyFunctions")` wiring of ~30 downcalls into that helper. ArchitectureTest already requires `invokeExact` only in `SyscallInvoker`; this issue does not move that.

**Needed:**
1. Collapse per-syscall `invokeExact`+`getErrno` into one helper that still captures errno immediately. Do not change layouts or 32-bit field types.
2. Either use `gettid` errno or stop capturing it; do not capture-and-drop.
3. Run `:platform:test`. No kernel install tests required.

## Side effects
- None intended; internal helper only

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

**Resolution evidence (2026-09-07):** `SyscallInvoker` centralizes immediate errno capture in
`intCall` and `longCall`; the pointer-returning mmap call preserves its required address
conversion. `gettid` unwraps an errno-aware result rather than capturing and dropping errno.
`./gradlew :platform:test` passed.

<!-- id: issue-20260907-081116  file: issue-20260907-081116-deduplicate-syscallinvoker-errno-capture-boilerplate.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
