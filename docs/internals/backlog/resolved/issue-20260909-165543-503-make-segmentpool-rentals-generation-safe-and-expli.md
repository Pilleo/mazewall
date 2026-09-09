---
title: "Make SegmentPool rentals generation-safe and explicitly owned"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
  - ":profiler"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/memory/SegmentPool.kt"
target_symbols:
  - "SegmentPool"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make SegmentPool rentals generation-safe and explicitly owned

**Context:**
Address-only return lets a stale checkout release a later rental of the same buffer; implicit arenas cannot be closed safely.

**Needed:**
1. Replace raw rentals with closeable generation leases and explicit owned or borrowed factories.

**Resolution:** `SegmentLease` validates exact pool/generation identity and rejects its segment accessor after return. The pool's synchronized rent/return/close operations reject outstanding-close atomically, allow retry, and reject rentals after successful close. Owned pools close their shared arenas; borrowed pools leave arena lifetime to the caller. Global daemon pools remain process-lifetime owners. All repository callers now close leases, and partial session-buffer acquisition rolls back acquired rentals and ACK storage. Public constructors and raw-segment release were removed under the approved scoped API break.

**Evidence:** The original ABA regression failed with two active renters sharing an address (one of seven tests failed). Lease tests then passed for stale/idempotent close, getter rejection, foreign/wrong-size returns, outstanding-close retry, owned/borrowed lifetime, rent-after-close, argument validation, concurrent exclusivity, zeroing, layout alignment, overflow reuse, and partial session acquisition. `./gradlew --no-daemon :platform:test :platform:detektMain :profiler:test :enforcer:compileKotlin :portal:compileKotlin :portal-worker:compileKotlin :portal-codegen:compileKotlin --continue` passed in 56 seconds. Migration documentation is [here](../../designs/api/segment-pool-migration.md).

---
<!-- id: issue-20260909-165543-503-make-segmentpool-rentals-generation-safe-and-expli  file: issue-20260909-165543-503-make-segmentpool-rentals-generation-safe-and-expli.md -->
