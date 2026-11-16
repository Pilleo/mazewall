---
title: "Diagnose wrapped profiler concurrent-read future timeout"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - "platform"
  - "profiler"
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProfilerIntegrationTest.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerDaemonManager.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerSessionHandler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerPreparationPool.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerDaemonTest.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/memory/TraceeMemoryReader.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerPreparationMain.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerPreparationPoolTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerPreparationProtocolTest.kt"
target_symbols:
  - "ProfilerIntegrationTest"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose wrapped profiler concurrent-read future timeout

**Context:**
Full gate015 reached profiler fresh-JVM integration and failed the four-thread ten-read wrap test at its unchanged five-second Future.get deadline. Deduplication assertion was not reached. Phase, worker and daemon state at timeout were absent; no root cause is established. This is distinct from issue20261003-131111-752 process-wide missed-event and cold-network signatures.

Diagnostic016 repeated the same third-in-class wrap failure: 15 cases, one failure, zero skipped; the failed test took 6.764 seconds. At timeout all four active workers had passed install-return and were in task-body phase 1, with native `FileInputStream.open0` atop their captured stacks; six queued tasks remained in phase 0. This occurrence was not an install-phase stall; the deduplication assertion was not reached and no root cause is established. The five-second Future.get deadline was unchanged; the separate JUnit default was two minutes. Gradle and container exited 1; the container physically retired without OOM or guard kill. Policy and recorded input checks passed, and classpath manifests were retained. Full gate015 remains failed.

See the [diagnostic016 outcome and evidence links](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/diagnostic016-outcome.md).

The operator approved the temporary metadata-only daemon-progress diagnostic and **one** further whole-class run, within the exact [diagnostic017 plan](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/daemon-progress017-plan.md). Records are strictly validated and capped at 64 emitted per daemon and 64 forwarded per parent JVM, with no paths, raw payloads or arbitrary child output. Workload, deadlines, capabilities, policy and defaults remain unchanged; temporary diagnostic changes must be removed before qualification. This approval is not a functional fix or qualification.

The later 018 focused checker records 25 actual passing cases (4 lifecycle, 9 pool, 3 protocol, 4 native-wrapper, 5 diagnostic), zero skips/errors/failures, and passing integration Kotlin compilation plus Main/test ktlint. Its actual-JVM synthetic codec roundtrip and strict stderr validation are positive evidence only; fixtures are not kernel/readiness evidence. Correction: close/launch did not force close to begin before launch release, only observe child death before close-future completion; the 5,000-record stderr fixture accepts any `ExecutionException` and does not prove all records drained or cap-pressure completion. Pool-level stuck-reader/interruption/primary-failure, deterministic ordering, and full-drain proof remain unproved. Reviews: [checker review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/helper-progress018-checker-review-001.md), [integration review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/helper-progress018-integration-review-001.md). Controller accepts these gaps only for one temporary guarded diagnostic, not shipping acceptance or permanent bypass; restore temporary code/factory before qualification. Full015 remains failed, B4 blocked, B5 inactive.

Diagnostic018 consumed that one authorized run and FAILED: 15 cases, one failure, zero skips, same five-second Future timeout; the fresh-JVM task executed, not cached. Policy/profile/input checks passed (UID/GID 1000, NNP 1, five checkpoint masks), physical PID 0, no OOM or guard kill.
See [018 analysis](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/diagnostic018-analysis.md), [timeline](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/diagnostic018-timeline.tsv), and [independent record audit](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/diagnostic018-record-audit/onlyforreceipt/records/report).
Four initial request-response intervals were 4893–5092 ms; INIT_BEGIN→END was the largest bracket in all four (2680–3006 ms), including snapshot/memory/wrapper/factory initialization and scheduling.
Typed native calls reported success (code 0), taking 89–187 ms including dispatch and scheduling, not pure kernel time. Neither bracket identifies one responsible operation; no concrete fix is justified.
The prior retired case has a separate daemon, not warmup; a later partial record proves no success, and absence under the shared cap is inconclusive.
Record audit PASS: 64 records, max 69 bytes, no malformed/unexpected phases; source-log SHA-256 `ec798c3265e07b137b2f2cc20c61fb52e7ed8e04ddc60f14229a4d3b40fc0875`.
No retry/further run is licensed. Static attribution alone authorizes no new experiment or functional fix. Temporary diagnostic/factory must be removed before qualification; full015 failed, B4 blocked, B5 inactive; prior checker corrections and coverage gaps remain.

Diagnostic017 consumed that run and failed: 15 cases, one failure, zero skips; the same third wrap test timed out after 7.001 seconds. Four workers were in body phase 1 at native file open and six tasks remained in phase 0. The 64-record parent cap was reached; four first PID 311 request-to-response intervals were 4480–4882 ms, while two later observed intervals were 1 ms. Missing ACK records are not proof of failure or absence; replies can be sent without ACK. No root cause is established. See the [outcome and correlated timeline](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/diagnostic017-outcome.md). No further run is authorized; full015 remains failed, with no B4 acceptance or B5 activation.

The four newly added temporary diagnostic files are preserved in the [frozen 018 source snapshot](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/container-full-gate-inputs-018/source/), together with the instrumented versions of existing files. Failed runs and diagnostic evidence remain retained.
Cleanup source checks confirm eight existing files restored to their exact pre-investigation 015 bytes and four temporary files absent from the working tree. All 3,021 original non-Markdown code/config checksums match, the 4,296 eligible paths match, HEAD is unchanged, and the index is empty. Seven original pool/protocol host cases passed freshly; compilation and formatting checks also passed with the cache limitations recorded in the [cleanup verification](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/profiler-wrap-fix-001/cleanup019-verification-002/). Final documentation checks remain separate. This restoration does not fix the timeout or qualify B4.

Historical diagnostic scope included an identity-preserving temporary internal `TraceeMemoryReader.real(memory, fileSystem)` factory. Cleanup restored the original platform file byte-for-byte; no new engine API or behavior is retained.

**Needed:**
1. Retain the original workload and deadlines, capture bounded task phases and actual worker stacks on timeout, and diagnose the owning class context before any functional change or retry-to-green claim.
2. Attribute cold necessary initialization without observer/scheduling confounds before selecting a behavior change. No further diagnostic run or fix is authorized by the completed investigation.

---
<!-- id: issue-20261106-161141-509-diagnose-wrapped-profiler-concurrent-read-future-t  file: issue-20261106-161141-509-diagnose-wrapped-profiler-concurrent-read-future-t.md -->
