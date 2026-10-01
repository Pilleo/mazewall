---
title: "Validate captured interpreted HotSpot method identity before cached lookup"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/patches/**"
  - "experiments/hotspot-agentless/scripts/prepare-gadget.py"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate captured interpreted HotSpot method identity before cached lookup

**Context:**
The pinned profiler's `interpreter/hotspot/instance.go:260` declares
`getMethod(addr libpf.Address, _ uint32)` and accepts the existing method cache
entry using the address alone. Interpreter capture sends `cmethod >> 3` as
`ptr_check`, and `Symbolize` passes it to `getMethod`, but it is ignored. Compiled
JIT lookup separately checks its compilation ID. Quality metadata cannot prove
that an interpreted cached method belongs to the captured incarnation.

**Needed:**
1. Add an upstream regression with reused method address and changed captured
   ConstMethod check; assert no old method attribution.
2. Determine a bounded validation strategy preserving existing cache ownership,
   read-error behavior and fail-closed attribution; prepare an independent patch
   candidate for review (upstream submission requires operator action).
3. Verify class-unloading/reuse behavior before accepting the runtime identity
   matrix. Do not claim the current quality patch resolves this issue.

**Bounded fix implemented locally; review/runtime matrix pending:**
`profiler-identity.patch` routes interpreted frames through an explicit checked
lookup, including token zero. Two checked eight-byte reads bracket cached/decoded
method lookup. It compares the captured `(ConstMethod >> 3)` low 32 bits, retains
the full ConstMethod address in the existing method cache, reloads a changed
cached address, and rejects replacement during lookup. Read failures remain
wrapped errors (EPERM/EACCES included); no unreadable-pointer-to-zero fallback.
Remote-memory bias and short reads are handled explicitly.

Red regression reproduced stale frame emission and cache attribution despite
unreadable memory. Green tests cover matching cache, changed address/reload,
replacement during lookup, valid zero token, null/overflow/short reads, bias and
permission failures. `make verify` and live Xint/tiered quality/recovery gates
passed. Artifacts: `capture.Sqmafu`, `capture.eFqJZ6`, `capture.xzcjqm` under the
experiment's ignored `build/` directory.
Backlog validation and `./gradlew build` passed after focused checks.

**Remaining identity limitations:** the producer fingerprint is truncated and
cannot distinguish all address reuse/ABA cases. Frame-cache reuse and compiled
scope lookup retain existing behavior. The bounded checks do not establish a
generation identity or replace the class-unloading/reuse acceptance matrix.

---
<!-- id: issue-20260930-220901-114-validate-captured-interpreted-hotspot-method-ident  file: issue-20260930-220901-114-validate-captured-interpreted-hotspot-method-ident.md -->
