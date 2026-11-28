---
title: "Decouple mandatory security scans from NVD updates with fresh trusted snapshots"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - "core"
  - "buildSrc"
target_files:
  - "build.gradle.kts"
  - "buildSrc/build.gradle.kts"
  - "buildSrc/src/main/java/io/mazewall/build/SecuritySnapshotVerifier.java"
  - "buildSrc/src/main/java/io/mazewall/build/VerifySecuritySnapshotTask.java"
  - "buildSrc/src/test/java/io/mazewall/build/SecuritySnapshotVerificationFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationWorkflowInputFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/CiCacheKeyFunctionalTest.java"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - ".github/workflows/ci.yml"
  - ".github/workflows/security-data.yml"
  - "scripts/security_snapshot.py"
  - "scripts/test_security_snapshot.py"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols:
  - "SecuritySnapshotVerifier"
  - "VerifySecuritySnapshotTask"
  - "CheckCiCachePolicy"
open_questions: false
---

# Decouple mandatory security scans from NVD updates with fresh trusted snapshots

**Context:**
The mandatory dependency analysis currently performs an NVD update and scan in one CI job. Upstream NVD HTTP 503 responses have repeatedly stopped the job during database update, before analysis runs. The latest retained remote run restored its prior snapshot but still failed the update after retries, so it produced no scan result. This is an availability failure, not a clean scan and not evidence that the dependency graph is vulnerable or free of findings.

The approved design separates a trusted snapshot producer from read-only mandatory scan consumers. A snapshot is usable only after strict metadata/database verification and only when it is no more than four hours old. At present there is no qualified fresh trusted seed. Keep all scan consumers fail-closed until a seed from an approved producer exists; do not claim the new workflow can run successfully on the default branch before it has been merged and produced a verified snapshot.

**Security and scope constraints:**

- Preserve mandatory scanning, current scanner version and database schema 5.6. Keep the existing CVSS 7 threshold, `failOnError`, and failure/cancellation behavior unchanged. The PR scan must not use an NVD API key or perform an automatic database update.
- The only trusted producers are a scheduled or manually dispatched default-branch updater that uses the real NVD API and verifies completion before publishing, or the existing default-branch CI push bootstrap while that compatibility path is retained. Both must satisfy the same four-hour freshness limit. Never trust a same-PR/head artifact, mutable PR cache, or arbitrary cache entry.
- Snapshot verification is read-only: do not create or write the H2 database. Reject missing/invalid metadata, wrong schema, missing or zero CVE rows, malformed/future/stale timestamps, failed or incomplete updates, and unexpected database ownership/URL state. Verify the actual database and metadata before publication and before consumption.
- Add no dependency, scanner suppression, threshold relaxation, native API, privilege change, credential-value edit, worktree, or cache-copy workaround. Do not disable the mandatory scan or turn an upstream failure into success.

**Needed:**

1. Implement and test a strict verifier for the actual H2 `nvd.api.last.checked` metadata. Apply the four-hour `dependencyCheck` freshness interval and check schema 5.6, nonzero CVE data, parseable `lastModified`, and `lastModified <= checkedAt <= now`; reject stale and future timestamps, absent metadata/rows, tampering, unsafe URL injection, and non-read-only database access. Verify that verification itself does not create or mutate the database.
2. Add a scheduled-hourly and manually dispatched default-branch snapshot producer. It must force a real NVD API update, require a complete successful updater result with no omitted CVE data, validate the database owner/state and snapshot, and only then publish an immutable artifact. Keep the legacy default-branch CI push bootstrap trusted only under the same strict validation and four-hour freshness rule; do not trust PR-produced or mutable PR cache data.
3. Change mandatory CI consumers to obtain only a verified fresh snapshot from those trusted default-branch producer paths. Make the scan task read-only, with no API key and auto-update disabled, while preserving the current scan action, severity threshold, `failOnError`, mandatory status, and failure/cancellation propagation. If no trusted fresh seed exists or verification fails, fail closed.
4. Add functional tests using actual H2 behavior for valid fresh data; stale/future timestamps; wrong schema; missing metadata or database; zero CVE rows; tampering; URL injection; read-only/no-create behavior; the effective 13 scan arguments/mode; and workflow trust, failure, and cancellation behavior. Retain negative controls proving same-PR artifacts and mutable caches are rejected.
5. Run focused verifier and workflow tests, the full local quality gate, independent review of the exact scoped diff, and fresh required CI after a trusted seed is available. Record actual producer and consumer receipts. Keep this issue OPEN until the producer has published a verified fresh snapshot and mandatory CI scans complete successfully.

## Focused implementation status (2026-11-15)

The scoped implementation and focused verification are complete, but this issue remains **OPEN** pending the full local gate and a successful trusted producer/consumer CI cycle. The six Python snapshot tests passed. GREEN003 passed the real Dependency-Check/H2 plugin contract and keyless scan-task wiring: analysis entered the expected downstream failure without making an NVD connection. GREEN004 passed the 625-result policy projection, cache invalidation, and nonvacuous failure-marker controls; root ktlint and warning-ratchet checks passed in GREEN003. Independent source review 002 reported no blocker in the reviewed changes. These results do not constitute a full build, successful scan, or fresh trusted seed.

The controller-approved historical default-branch bootstrap attempt 2 failed at `Update Security Data` with NVD HTTP 503 after retries. It produced no fresh security snapshot; the mandatory security check was skipped, the aggregate failed, and publication was skipped. The Optional job failure shown in that attempt was carried forward from October 31 and was not rerun. There is therefore no successful scan result to report. Full local verification and fresh required CI remain pending, and the issue must stay open until a trusted fresh snapshot is produced and mandatory scans complete successfully.

## Focused implementation status (2026-11-15, selector follow-up)

The Python snapshot/selector suite now passes seven tests in GREEN001, including rejection of malformed/out-of-range producer attempts, trust bounds before attempt lookup, and fail-closed handling of genuine API errors. Independent source-level selector review 004 found no blocker. The policy suite passed its 625-result four-variable projection plus separate Go, optional-integration and confined-helper join controls, cache invalidation, and nonvacuous failure-marker checks. The required workflow join has seven lanes and requires all seven to succeed.

Full001 was intentionally interrupted at exit 137 while addressing the bounded availability issue; it is not a completed quality gate. The final full gate and fresh required CI remain pending. The earlier bootstrap attempt 2 still failed at NVD HTTP 503, produced no fresh snapshot or successful scan, and skipped publication. This issue remains OPEN; do not infer a clean scan, a fresh trusted seed, or deployment success from the focused test/review results.

## Full003 local gate status (2026-11-15)

Full003 passed the offline incremental Gradle quality gate in 15m08s (238 actionable tasks: 52 executed, one from cache, 185 up-to-date; configuration cache reused). Executed Enforcer integration and FreshJvm integration reported 244 tests/2 skips/0 failures/errors and 120/1/0/0. Enforcer unit results 1,639/2/0/0 were up-to-date, not freshly run. `buildLogicCheck`, warning ratchet and enforcer kernel coverage executed; nested `verificationActionsTest` and unit coverage verification were up-to-date. Source/index/HEAD remained unchanged and the owned container retired cleanly. Full003 report SHA-256: `bc075a2cb59de2a26593b353b9e3bef99ab8bdb1d8f05da7e4dd90b5636e40d5`.

This is an incremental local build, not a live NVD scan or fresh trusted-seed qualification. Three profiler socket `Function not implemented` shutdown exceptions and three suppressed directory exceptions remain; issue 752 and individual profiler-child retirement are still unknown. The post-build preferences-directory IOException is retained without inferring further consequences. Bootstrap attempt 2 remains an NVD HTTP 503 with no fresh snapshot or scan; the mandatory security check was skipped, the aggregate failed, and publication was skipped. Fresh required CI and a successful trusted producer/consumer scan remain pending. Issue 021 stays OPEN.

<!-- id: issue-20261115-132820-021-decouple-mandatory-security-scans-from-nvd-updates  file: issue-20261115-132820-021-decouple-mandatory-security-scans-from-nvd-updates.md -->
