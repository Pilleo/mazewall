---
title: "Compare warm Portal calls with executor and persistent HTTP baselines"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "benchmarks"
target_modules: []
target_files:
  - "benchmarks/integration-boundaries/README.md"
  - "benchmarks/portal-comparison"
target_symbols:
  - "PortalRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Compare warm Portal calls with executor and persistent HTTP baselines

**Context:**
Portal developer-experience plan P01: establish comparable cold and warm latency, throughput, allocation and process-footprint evidence before promising lower overhead than an API service. Keep the historical four-mode checking dataset unchanged; coordinate with the existing packaged-application adoption issue.

**Needed:**
1. Add an independent `benchmarks/portal-comparison` build using the existing staged-artifact/strict-lock workflow, with direct, warmed executor, packaged Portal and persistent loopback HTTP child-JVM modes.
2. Use equivalent binary payloads and useful work; retain cold/startup, first-call and warmed results separately. Keep the historical harness, locks and dataset intact.
3. Add assert-based correctness/accounting self-checks and bounded measurement tasks. Record payload/concurrency sweeps, offered load, failures, tail latency, throughput, CPU, allocation and per-process memory.
4. Add a separate memory pass for host-only baseline, cold/warm idle, busy/queued work, consumed versus retained results, worker-cache replication and replacement/close. Sweep one/two/four total workers and one/multiple groups without multiplying the whole latency matrix.

**Plan and dependencies:**
- [Implementation plan P01](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [measurement contract](../designs/portal/portal-developer-experience.md#9-evidence-and-optimization-policy).
- Starts from the current runtime. Reuse the [representative adoption issue](issue-20261028-230731-805-measure-realistic-packaged-application-adoption-an.md) for external-app SLOs and integration effort; this issue owns the controlled comparison.

**Acceptance:**
- All modes pass an independent output oracle; corrupt output fails the run. Portal and HTTP work execute in observed child PIDs, with no guest implementation loaded into the host.
- At least three fresh-JVM forks and 10,000 warm observations per reported p99; fixed offered-load and closed-loop results retain accepted/rejected/timeout accounting.
- Persistent HTTP uses a reused connection and equal binary work under comparable core/worker budgets. Protection differences and collection scope are disclosed. No universal faster-than-HTTP claim follows from the data.
- Exclude only benchmark oracle checks, environment fingerprinting and observer logging from timing. Production admission, snapshots, encoding/decoding and reply validation remain inside measured end-to-end calls; cold/startup timing includes required artifact hashing and launch/readiness validation.
- Record exact artifact/environment identities, raw samples, finite outer budgets and actual child death after close. Update later comparisons through their owning feature issues.
- Report host and worker RSS/PSS/private memory, sampling intervals and authorized cgroup current/peak/events. Distinguish summed PSS from double-counted summed RSS; do not add parent/child cgroups. Denied/missing samples are unavailable, never zero.
- Include DTO decode expansion, caller result retention, duplicate worker caches and configured heap/cgroup/request limits. Equal-work HTTP comparisons include its JVM too; no heap-cap-to-RSS or thread-equivalent footprint claim is allowed.
- Memory/NMT diagnostic sampling is outside the principal latency pass. NMT coverage/overhead and containment-related attach unavailability are explicit; policy is not weakened for measurement.

**Verification:**
- [Junior implementation walkthrough: P01](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p01--fair-performance-and-whole-deployment-memory-measurements) defines timing boundaries, conservation accounting, memory units/scopes, future task commands and required raw evidence. Start timestamps precede submit-time snapshots; completion timestamps do not depend on a serial result collector.
- Run the new `portalComparisonSelfCheck` and `portalComparison` tasks against staged pinned artifacts; document their exact commands in the new build's README. Use JDK HTTP facilities and Kotlin checks without a new third-party dependency.
- Run `./gradlew build`, `./scripts/adkw check-backlog` and `git diff --check` at implementation completion; the independent comparison is an additional gate, not selected by the root build.

---
<!-- id: issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis  file: issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis.md -->
