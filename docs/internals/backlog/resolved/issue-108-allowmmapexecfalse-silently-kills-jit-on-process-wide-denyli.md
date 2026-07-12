---
title: "`allowMmapExec=false` silently kills JIT on process-wide DENY_LIST policies"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: `allowMmapExec=false` silently kills JIT on process-wide DENY_LIST policies

**Target:** `Policy.NO_NETWORK` KDoc, `designs/enforcer/containment-design.md §3f`
**Context:** `allowMmapExec` defaults to `false` on ALL policies, including DENY_LIST presets like `NO_NETWORK`. When installed process-wide via `installOnProcess()`, the BPF filter applies to JIT compiler background threads, blocking their `mmap(PROT_EXEC)` code-cache allocation calls. Result: fatal JVM abort (`os::commit_memory failed; error='Operation not permitted'`). Discovered by removing `-Xint` from `IsolatedProcessTester` — the flag had been masking this crash in integration tests.
**Fix:** Added `### JIT Compiler Warning` to `Policy.NO_NETWORK` KDoc documenting the footgun and the correct workaround (`Policy.builder().base(NO_NETWORK).allowMmapExec().build()`). Added `§3f` to `designs/enforcer/containment-design.md` with the full failure pattern. Fixed `testNioStability()` in `ProcessContainmentTest` to use the correct derived policy.
