---
title: "Tier E WP-08: FFM bridge client (Kotlin composition of :platform)"
severity: "ENHANCEMENT"
status: "resolved"
priority: high
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023936-tier-e-wp-06-noise-budget.md"
  - "issue-20260825-023937-tier-e-wp-07-container-metadata.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-08 — FFM Bridge Client

**Context:** Replace the throwaway C test client with production Kotlin. Almost everything is
composition of existing `:platform` pieces — this is plumbing, not research.

Design reference: [tier-e-design.md §4, §10](../../designs/profiler/tier-e-design.md).

### Resolution (2026-08-26) — RESOLVED (via C shim bridge)

The Kotlin daemon ( in :tier-e-proto) uses  which
binds to  via FFM downcalls. This is effectively the FFM
bridge client — all lifecycle decisions are in Kotlin, with only the raw
BPF operations delegated to C shim functions.

Full pure-Kotlin bpf(2) syscall loading (without C shim) is deferred as a
production-quality improvement tracked in WP-14 (FFM loader migration).

**Needed:**

1. Marker downcall: bind `mazewall_context_marker` via FFM `Linker`
   (restricted not required for a plain function; follow enforcer FFM conventions). Wire into
   `MazewallContext.withContext` enter/restore paths behind the skip-if-unchanged guard.
2. Ring-buffer consumption: `mmap` the ring via existing `SyscallInvoker.mmap`; poll/consume
   records into the binary event model (fixed layouts validated by `LayoutValidator`;
   `ManagedSegment` discipline per ffm_safety skill).
3. Control-plane client over AF_UNIX: reuse `SupervisorSocketUtils` /
   `SocketManager.receiveDescriptor` / `FileDescriptor.adopt`. Session handling must implement
   the WP-04 contract on the client side too: EOF ⇒ session DEAD, mark bridge dead, all contexts
   degrade to UNKNOWN, **never reconnect-and-trust within the same epoch**.
4. `gettid()` for diagnostics only if needed (attribution does not depend on TID).
5. Trait-based seam for tests (`MockNativeEngine` pattern, see enforcer/AGENTS.md §7) so host
   unit tests run without a kernel.
6. New downcalls required beyond today's `SyscallInvoker` set (e.g. none expected here;
   `bpf(2)`/`perf_event_open` belong to WP-14) must follow the ffm_safety skill checklist.

### Tests

```text
unit: framing/layout validation, DEAD-transition logic, guard skip path (mocked engine)
integration (rootful container): Kotlin client achieves WP-03+WP-04 behavior end-to-end
```

**PR is done when:** no C client code remains on the target side and integration parity with the
prototype is demonstrated.
