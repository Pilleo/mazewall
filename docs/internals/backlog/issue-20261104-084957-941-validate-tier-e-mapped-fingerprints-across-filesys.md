---
title: "Validate Tier E mapped fingerprints across filesystem device domains"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "profiler/src/test/resources/tier-e-a4-fingerprints.py"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/backlog/issue-20261104-084957-941-validate-tier-e-mapped-fingerprints-across-filesys.md"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "freezeFingerprints"
  - "fingerprint_mappings"
  - "diagnosticSources"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate Tier E mapped fingerprints across filesystem device domains

**Context:**
Cycle002 loaded BPF and attached the tracer but failed preREADY because /proc/maps reported Btrfs superblock device0:34 while stat of the same agent inode94075261 reported subvolume device0:50. A fresh read-only self-mmap owned-container probe confirmed /proc/self/map_files opened the same stat device/inode and identical SHA256. Comparing maps device directly with stat device is invalid on Btrfs.

**Needed:**
1. Fingerprint the opened owned-target map_files object and compare its descriptor identity with the opened target-root path, preserving mismatch and permission failures. Add a shared behavioral regression, independently review, then qualify with a fresh unchanged20+20 diagnostic.

**Host correction (2026-11-04; integration pending):**
The complete private fingerprint program is now a test resource frozen in
`diagnosticSources`. Each selected range opens its owned-target `map_files` object
and target-root file, requires regular files and equal descriptor device/inode
identities (also retaining the maps inode check), and hashes the first verified
mapped descriptor once per file while owned. Every selected range is still
validated; raw maps identities and both descriptor identities are retained.
No guessed filesystem exception or pathname/hash-only identity acceptance exists.

Host regressions execute this resource through Python `runpy` and real temporary
file descriptors, injecting only proc path opening and stat device observations.
They cover cross-domain maps/stat devices, different descriptor device/inode,
byte-identical replacement, later mapping mismatch, missing agent/libjvm or map
object, nonregular objects, permission/read/hash errors, descriptor closure, and
pinned hashing after pathname replacement. The focused support suite passes
63 tests, zero skips/failures; integration compilation and diagnostic wiring pass.
The observed RED was the original maps-device assertion, not a missing symbol.

Remain **open** for independent review, controller-owned fresh cycle003 with the
unchanged 20+20 workload, and the final full gate. Cycle002 and probe evidence
remain immutable; host success does not qualify a diagnostic arm or resolve the
historical missing-open cause (A4 FAILED; A3 unresolved).

---
<!-- id: issue-20261104-084957-941-validate-tier-e-mapped-fingerprints-across-filesys  file: issue-20261104-084957-941-validate-tier-e-mapped-fingerprints-across-filesys.md -->

**Current availability (2026-11-16):** The approved retirement of the consumed one-shot A4 LIVE path removes the current fresh-diagnostic route requested above. This issue remains open and its fingerprint/permission requirements are not qualified by this cleanup. Preserve the original 20+20 requirement and historical evidence; any future LIVE route requires separate authorization. Replay remains available; G4 is unresolved. See [retirement issue 079](issue-20261116-072155-079-retire-consumed-tier-e-a4-one-shot-live-diagnostic.md).
