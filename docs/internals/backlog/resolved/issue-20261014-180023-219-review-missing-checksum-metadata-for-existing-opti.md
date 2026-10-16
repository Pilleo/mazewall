---
title: "Review missing checksum metadata for existing optional Spring Boot build inputs"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "gradle/verification-metadata.xml"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Review missing checksum metadata for existing optional Spring Boot build inputs

**Context:**
After the approved47 Koog checksum records and complete bootstrap/package migration pass, actual optional Spring Java compilation fails strict dependency verification for83 additional artifacts selected by existing Spring7.0.9/Boot4.1.1 and their BOM/compile graph. All83 unique report rows classify missing checksum approvals. Evidence: run-460bd9f898ff and run-042f8e41faef; build/reports/dependency-verification/at-1792000594410/dependency-verification-report.html. This expanded trust set is not part of the operator's47-artifact Koog approval.

**Needed:**
1. Obtain approval for the additional existing Spring/Boot checksum set, independently review acquired artifact bytes and pinned metadata, preserve all existing entries and strict checking, then verify actual optional producer compilation before finished-Boot harness qualification.

## Resolution evidence

The operator instructed continuing after the policy-history correction: the manifest
was introduced by Gradle modernization (`342ac834`), not present in the operator's
old main checkout. The cleanup maintains that inherited policy consistently.

- `run-8484c194a30f`: all83 originally reported missing artifacts independently
  acquired from HTTPS Maven Central and byte-equal selected cached files.
- The standard Gradle metadata owner also resolves existing test/runtime graphs.
  `run-c9568e68f98c`: all154 generated missing entries (original83 plus71 from those
  configurations) independently checked, with all prior approvals/config unchanged.
  Receipt `.superpowers/sdd/2026-10-14-repository-cleanup/spring-checksum-review/complete-review.json`,
  SHA256 `625b04a04f0625ff8f9a748966a198a414cb518b47f845c81fe1cbefaa8cb8a4`.
- `run-16821e2f5bc3`: actual optional producers pass normal strict verification,
  without metadata-generation flags or dependency declaration/version changes.
- `run-76fee390531e` / `run-387b4fb9f7ee`: new local qualification publication and
  actual standalone finished Boot executable built from private committed current
  source `c7c759d1366b852b65ca30dbdb35d24f3ae3a39a`, version
  `0.1.0-cleanup.c7c759d1366b`; no operator branch update or remote publication.
- `run-99cf5291398b` / `run-3003d32c7237`: Kotlin external fixture review, actual
  required native effects/negative cases and all14 real measurement collision
  cases pass, zero failures/errors/skips. Historical P617 identities unchanged.

---
<!-- id: issue-20261014-180023-219-review-missing-checksum-metadata-for-existing-opti  file: issue-20261014-180023-219-review-missing-checksum-metadata-for-existing-opti.md -->
