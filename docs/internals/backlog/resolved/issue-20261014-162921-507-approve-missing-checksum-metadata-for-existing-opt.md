---
title: "Approve missing checksum metadata for existing optional Koog 1.3.0 build inputs"
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

# 🟡 [Severity: MEDIUM]: Approve missing checksum metadata for existing optional Koog 1.3.0 build inputs

**Context:**
Real isolated consumer bootstrap at cleanup base96789e51 fails strict dependency verification for47 already-declared Koog1.3.0 and transitive artifacts; the report classifies them as missing checksums, not mismatches. No verification bypass or dependency change is approved. Evidence: run-7a71f21e372c and run-0bd44ea51ab2; retained clone /tmp/mazewall-consumer-bootstrap-7195703546979206423/checkout.

**Needed:**
1. Obtain operator approval to extend the cleanup file scope to checksum metadata, independently inspect the already-pinned artifacts, then add reviewed SHA-256 records without changing coordinates or disabling verification.

## Operator decision — 2026-10-14

The operator approved adding independently reviewed SHA-256 records for these
already-pinned dependencies in `gradle/verification-metadata.xml`. Existing
verification entries, strict checking and declared coordinates must remain intact.
Implementation and actual strict-build acceptance are still required before closure.

## Resolution evidence — 2026-10-14

- `run-61b6f15449a3`: all 47 selected artifacts match independently acquired HTTPS
  Maven Central bytes; 16 JARs also match retained accepted P617 classpath receipts.
  Reviewed multiplatform metadata parent links and JAR integrity. Retained receipt:
  `.superpowers/sdd/2026-10-14-repository-cleanup/checksum-review/review.json`, SHA-256
  `fbb517e470691bcd1bc7153ce37ddd4dd6d385be07ca01a37cb0238159707e4f`.
- `run-b4f45e3a3454`: the exact 47 additions match that receipt; every pre-existing
  approval and the strict verification configuration remain byte-preserved.
- `run-951592e032e7`: previously failing actual optional build passes with strict
  dependency verification. No coordinate/declaration change or verification bypass.
- `run-198fa87cd579` / `run-2f3538fb8017`: complete disposable-source bootstrap,
  freshness/exclusion/failure cases and both actual opaque package variants pass;
  29 named CLI cases plus the direct exclusion case, zero JUnit failures/skips.

This closes missing checksum metadata. Finished Boot/native consumer qualification
and the remainder of the repository cleanup retain their separate acceptance gates.

---
<!-- id: issue-20261014-162921-507-approve-missing-checksum-metadata-for-existing-opt  file: issue-20261014-162921-507-approve-missing-checksum-metadata-for-existing-opt.md -->
