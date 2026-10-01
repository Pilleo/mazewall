---
title: "Roll back HotSpot metadata caches after a rejected strict decode"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-design.md"
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-plan.md"
  - "experiments/hotspot-agentless/results/identity-investigation.md"
target_symbols:
  - "getMethod"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Roll back HotSpot metadata caches after a rejected strict decode

**Context:**
Second review found that epoch validation delays frame-cache admission but getMethod/getSymbol/getStubName populate existing metadata LRUs during decoding. A failed decode at an unchanged epoch can leave fallback or partial records which a later cache hit can accept without reproducing the failed read.

**Needed:**
1. Define abort-time invalidation for all metadata caches written by strict decoding and test rejection followed by another definition at the same epoch.
2. Cover addrToMethod, addrToSymbol, addrToJITInfo and addrToStubName as applicable; frame-cache staging alone is insufficient.

**Reproduced diagnostic:** `build/epoch-review.r6i0tgok/probe.log` has two expected
failures: EACCES during a class Symbol read still produces cached `void .leaf()`,
and a retry with readable memory returns that rejected method record. The probe
uses real getMethod/LRUs with synthetic metadata and the proposed reader ledger;
it does not implement or exercise a production epoch guard.

**Closure evidence:** a failed end/decode at unchanged epoch must leave no
unvalidated metadata cache entries; a subsequent decode must read the correct
class/method names. Keep the original wrong-generation acceptance assertion.

**Implemented; independent review pending:** failed strict decoding purges all
four existing metadata LRUs; new frame-cache segments are admitted only after
finish. `TestHotSpotEpochRollbackAndSameEpochRetry` replaces the red cache-pollution
case with a permission-preserving rejection and correct same-epoch retry.
The actual frame-cache admission/hit tests and host race gate pass. Three fresh
strict unloading runs and final audited repeat resolve all late-generation
operations with zero wrong labels; the non-epoch control remains red.

---
<!-- id: issue-20261001-134316-242-roll-back-hotspot-metadata-caches-after-a-rejected  file: issue-20261001-134316-242-roll-back-hotspot-metadata-caches-after-a-rejected.md -->
