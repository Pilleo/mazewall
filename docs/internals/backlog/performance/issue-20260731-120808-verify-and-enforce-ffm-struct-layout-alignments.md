---
title: "Verify and Enforce FFM Struct Layout Alignments Against Native C ABIs"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/Layouts.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/LayoutValidator.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Verify and Enforce FFM Struct Layout Alignments Against Native C ABIs

**Context:**
The FFM (Foreign Function & Memory) API requires strict alignment of struct fields. If a `MemorySegment` or custom FFM struct layout (such as `Layouts.SOCKADDR_UN`, `Layouts.POLLFD`, `Layouts.IOVEC`, `Layouts.MSGHDR`, or seccomp structs) defines fields with incorrect alignment or misses padding bytes required by the target platform's C ABI (x86_64 vs aarch64), downcalls can trigger JVM crashes, silent data corruption, or `SIGBUS`/`SIGSEGV` signals.

While `LayoutValidator.kt` performs some basic assertions, it does not programmatically check struct alignments against the platform's compiler-enforced alignments.

**Needed:**
1. Enhance `LayoutValidator.kt` or implement a compile-time check using a small native C companion (e.g. `scripts/verify_offsets.c`) that outputs the exact `sizeof` and `offsetof` for all structures and fields used by mazewall.
2. Ensure `LayoutValidator` parses this output or compares FFM offsets (`MemoryLayout.byteOffset()`) against native offsets on boot. If any offset or alignment mismatch is detected, fail-closed immediately with a detailed explanation.
3. Explicitly verify structures that differ between x86_64 and aarch64 (such as `msghdr` padding or 64-bit alignment constraints).

**Verification/Regression Tests:**
- Validate that running the alignment check on a standard Linux x86_64 host passes cleanly.
- Verify that manual alterations to `Layouts` (e.g. changing field order or removing padding) are immediately caught and trigger a fatal initialization error.
- Run `./gradlew :enforcer:test` to verify layout correctness.
