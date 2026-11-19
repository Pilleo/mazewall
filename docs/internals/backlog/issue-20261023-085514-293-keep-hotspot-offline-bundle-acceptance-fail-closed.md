---
title: "Keep HotSpot offline bundle acceptance fail closed and producer compatible"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/superpowers/plans/2026-10-23-hotspot-remaining-acceptance.md"
target_symbols:
  - "VerifyBundle"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Keep HotSpot offline bundle acceptance fail closed and producer compatible

**Context:**
Task3 independent review reproduced rehashed receipt casing aliases overriding pending/error/incomplete states, valid after-worker counter changes being rejected, and missing known-resource cleanup receipts being accepted. Its proposed shared Go compiler receipt also cannot represent independently reused Go1.26 collector and Go1.27 node artifacts. These unpublished acceptance-checker defects must be corrected and reviewed before qualification.

**Needed:**
1. Add semantic RED/GREEN regressions for typed receipt aliases, before/after parsed worker identity with retained originals, per-artifact actual compiler identities, and exact known/shared acquired-resource UID retirement coverage; retain fail-closed errors and separate live custody from offline consistency.

**Review evidence (2026-10-23):** Task3's focused read-only overlay checks accept
`state:pending` followed by `State:retired`, original EPERM errors followed by an
`Errors:null` alias, and incomplete media followed by `Complete:true` after all
digests are updated. Checks also accept workload-Pod-only retirement and reject
valid worker originals differing only in a context-switch counter. Existing green
host suites therefore do not close this acceptance boundary.

**Scoped correction:** Task3 fix round1 owns typed-field-aware alias rejection
without changing real map-key semantics; parsed before/after identity comparison
without rewriting originals; artifact-associated actual compiler records; and
required exact Pod/profile plus consistent workload/control namespace and control
Pod retirement coverage. Actual resource UIDs and complete live inventory remain
Task4's responsibility; schema presence never proves custody or grants consent.
No live/native/image/cluster/fullgate checks have been run under this issue.

Keep this issue open through correction/review and the later owned-runner
integration; do not archive it using a checksum-only or synthetic acceptance claim.

**Reviewed host-source checkpoint (2026-10-23):** Task3 corrected all four
original Important findings. The owned runner's production-contract review also
corrected normal served-schema decoration, image archive/cwd and baseline startup
timing, acquired UID/gate revalidation before release, and final local permission
cause propagation. Final cross-task review found supported opaque metadata rejected
by blanket key folding: the runner now reuses the existing destination-typed
scanner, preserving exact opaque/data-map keys while rejecting actual duplicates
and typed authority/error/ownership aliases. One combined fix wave and one scoped
re-review close all source findings; actual caller/full synthetic bundle controls,
covering race and vet pass on the final bytes.

These remain host/synthetic checks, not actual origin/API/native/cleanup custody.
Original failures, pending/unknown owner handling and first diagnostics remain
retained. Released storage graph, real node permissions, independent artifacts and
live persisted-profile acceptance are still blocked; the issue remains open.

---
<!-- id: issue-20261023-085514-293-keep-hotspot-offline-bundle-acceptance-fail-closed  file: issue-20261023-085514-293-keep-hotspot-offline-bundle-acceptance-fail-closed.md -->
