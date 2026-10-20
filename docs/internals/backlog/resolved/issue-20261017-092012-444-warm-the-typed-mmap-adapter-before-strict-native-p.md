---
title: "Warm the typed mmap adapter before strict native protection probes"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: [enforcer]
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/MmapProtectionTest.kt"
target_symbols:
  - "MmapProtectionTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Warm the typed mmap adapter before strict native protection probes

**Context:**
The newly mandatory full commit build run-2f8bff6c9276 rejects a GraalVM fatal in pkey_mprotect probe setup: hs_err_pid3623280.log identifies SyscallInvoker.mmap offset120 cold MethodHandle linkage after executable mmap denial. Companion initialization creates an unused raw mmap handle but never invokes the typed NativeEngine path used by anonymousMapping. Warmup must exercise that exact production adapter before the filter, acquire only real native mappings and preserve checked cleanup; actual strict EPERM assertions remain unchanged.

**Needed:**
1. Replace unused raw mmap-handle construction with one owned non-executable allocation through the existing typed adapter before containment, preserve errno/failure/cleanup semantics and all protection assertions, and verify the actual complete committing build on GraalVM.

**Resolution:** Fixture initialization exercises the exact production mmap adapter
and existing acquired mapping/checked unmap path before containment. Unchanged
actual native protection assertions, focused GraalVM execution, formatting and
static checks pass; the committing revision is required to pass the full build
through the now-mandatory hook. See
[evidence](../../../plans/2026-10-17-typed-mmap-probe-bootstrap-evidence.md).

---
<!-- id: issue-20261017-092012-444-warm-the-typed-mmap-adapter-before-strict-native-p  file: issue-20261017-092012-444-warm-the-typed-mmap-adapter-before-strict-native-p.md -->
