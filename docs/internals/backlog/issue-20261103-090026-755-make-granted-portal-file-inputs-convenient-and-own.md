---
title: "Make granted Portal file inputs convenient and owned across async submission"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies:
  - "issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis"
  - "issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
  - ":portals:codegen"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/Capability.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalGrantRoots.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalInvocationRetirement.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalOperationOwner.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/OwnedPortalInvocation.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRequestWriter.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalOperationGenerator.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCapabilitySubmissionTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/OpenGrantedReadTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalGrantRootsTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalFileInputIntegrationTest.kt"
  - "tests/consumers/portal-plugin"
  - "benchmarks/portal-comparison"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "Capability"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make granted Portal file inputs convenient and owned across async submission

**Context:**
Portal developer-experience plan P08: use the existing broker-to-worker read-only descriptor boundary for large inputs; runtime grants and submit-owned descriptor duplicates should remove manual broker plumbing.

**Needed:**
1. Add a runtime service/root/relative-path grant facade over the existing broker/openat2 authority, including boxed Java selectors.
2. Duplicate direct inbound capabilities under bounded borrows during generated submission, consuming genuine `F_DUPFD_CLOEXEC` acquisition receipts.
3. Extend existing invocation retirement to own partial submit-time acquisitions and queued cancellation, as well as dispatched requests. Qualify a large-file consumer and measurement path.

**Plan and dependencies:**
- [Implementation plan P08](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [file ownership contract](../designs/portal/portal-developer-experience.md#7-file-capabilities-and-possible-bulk-buffers).
- Requires P03 submission ownership and P01 comparison. Reuse platform's existing fcntl acquisition receipts; no new syscall or raw FD adoption is planned.

**Acceptance:**
- Submit a real grant behind a held worker, close the caller's original, then dispatch: the child reads the intended bytes. A cancelled queued call retires duplicates while its caller-owned original remains usable.
- Acquisition/encoding/send failure, cancellation, worker death and close retain every owned duplicate until actual retirement. Pre-native close failure stays retryable; completed native close is not repeated.
- NOT_STARTED does not publish execution end before duplicate cleanup, including failures before dispatch where the current thread-local retirement hook is unavailable.
- Existing unknown-root, symlink/traversal and nonregular-file rejection remains intact. FD transfer stays broker-to-worker only and returned values remain copied.
- Explain shared open-file-description offsets and live mutable contents; duplication is not a content snapshot. Examples use fresh grants or positional reads.

**Verification:**
- [Junior implementation walkthrough: P08](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p08--acquired-file-capabilities-through-submission) details real acquisition, partial-failure cleanup, close-after-submit and shared-offset limits.
- Run capability/grant unit regressions and `./gradlew :portals:runtime:integrationTest --tests '*PortalFileInputIntegrationTest'`, including real close-after-submit and cleanup-retry cases.
- Run P01 byte-copy/file comparisons, `./gradlew build`, backlog validation and whitespace checks. Read the module/FFM ownership guidance before native acquisition work.

---
<!-- id: issue-20261103-090026-755-make-granted-portal-file-inputs-convenient-and-own  file: issue-20261103-090026-755-make-granted-portal-file-inputs-convenient-and-own.md -->
