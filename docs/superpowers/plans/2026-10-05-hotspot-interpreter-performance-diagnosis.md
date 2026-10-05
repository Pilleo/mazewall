---
document_type: execution_plan
base_revision: 2b2bc6c1
status: approved_inline_measurements_complete
---

# HotSpot Interpreter Performance Diagnosis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for the approved inline execution. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate interpreter asset admission, decoding, mapping and live finish costs and select an evidence-backed optimization.

**Architecture:** Opt-in internal asset timings, offline developer-only replay measurements, and retained paced/dense public workload outcomes. Existing validation, pairing, ownership and partial coverage remain authoritative.

**Tech Stack:** Kotlin/JDK25, existing kotlinx.serialization/JUnit5, pinned Temurin interpreter/SerialGC, existing rootful-container harness.

**Spec:** `docs/internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md`, `docs/internals/designs/profiler/profiler-design.md:587–642`, `profiler/HOTSPOT_AGENTLESS.md`.

## Global Constraints

- Linux-amd64, exact pinned Temurin25 `-Xint -XX:+UseSerialGC`, process-wide synchronous `openat_handler_entry` for live admission.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds; native original-event deadline/workers/buffers unchanged.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes; every asset checksum and transitive OCI validation preserved.
- No checksum cache, validation bypass, public API change, new dependencies, automatic download/compiler/privilege escalation.
- Coverage always incomplete; permission failures, partial results and cleanup owners preserved.
- Preserve unrelated worktree edits; scoped local commits, no push or delegation.
- Deliver diagnosis and an optimization decision, not a throughput guarantee; performance issue remains open.

---

## File Structure

| File | Responsibility |
|---|---|
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssetTimings.kt` | Optional monotonic phase timing |
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt` | Existing admission phases |
| `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotAssetsTest.kt` | Measured integrity/refusal |
| `profiler/src/tooling/kotlin/io/mazewall/profiler/hotspot/performance/HotSpotReplayMeasurement.kt` | Reader/mapper/final-construction timing |
| `profiler/src/tooling/kotlin/io/mazewall/profiler/hotspot/performance/HotSpotPerformanceMain.kt` | Offline asset/replay CLI JSON reports |
| `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotPerformanceMeasurementTest.kt` | Replay equivalence, refusal, settings |
| `profiler/build.gradle.kts` | Test/tooling friend association to preserve internal visibility |
| `profiler/src/tooling/kotlin/io/mazewall/profiler/hotspot/performance/HotSpotWorkloadMeasurementSettings.kt` | Validated workload counts/pacing |
| `profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotSupportedWorkloadMain.kt` | Paced/dense phase outcomes |
| `profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotPublicIntegrationTest.kt` | Guarded actual measurements/cleanup |
| `docs/plans/2026-10-05-hotspot-interpreter-performance-evidence.md` | Matched data/provenance/decision |

### Task1: Asset timings

**Interfaces:** Preserve `HotSpotAssets.load(Path): HotSpotAssets`; add internal `load(Path, HotSpotAssetTimings?)`. Timing owner accepts `clock: () -> Long`, exposes `measure<T>(String, () -> T): T` and immutable `snapshot(): Map<String,Long>`.

- [x] Read module/quality/spec instructions and run `./scripts/adkw blast-radius HotSpotAssets`.
- [x] Add failing measured-contract and tampered-helper tests using existing `fixture()`.
- [x] Run `./gradlew :profiler:test --tests '*HotSpotAssetsTest'`, observe missing-interface failure.
- [x] Implement timing with monotonic start/finally end and immutable copied map; null timing invokes original action without clock calls.
- [x] Wrap original phases in original order: manifest read/depth/provenance, checksum loop, OCI graph, collector executable/result.
- [x] Verify all integrity tests, timing exception preservation and immutable snapshot; commit `test(profiler): measure HotSpot asset admission phases`.

### Task2: Offline pipeline measurement

**Interfaces:** `measureReplay(InputStream, HotSpotReplayLimits): HotSpotReplayMeasurement` returns existing internal evidence, wire-record count, pipeline/mapping/finish nanoseconds. Main CLI: `assets <directory> <report.json>` and `replay <schema-1-jsonl> <report.json>`.

- [x] Add red measured-event-projection identity/ordered-frames equivalence and missing-summary refusal tests.
- [x] Run `./gradlew :profiler:test --tests '*HotSpotPerformanceMeasurementTest'` to observe missing implementation.
- [x] Measure original reader callback/assembler accept with `System.nanoTime`; measure `finish()` separately, preserve original protocol failures.
- [x] Write CLI with exact3args, current JVM version/arguments, phase durations, counters and `collection_loss_complete=false` using existing JSON library. `pipeline-mapping` is reader/decode/I/O remainder, not isolated parser CPU.
- [x] Verify wire/assembler/streaming regressions and `:profiler:installDist`; commit `test(profiler): add offline HotSpot pipeline measurements`.

### Task3: Paced/dense live outcomes

**Interfaces:** `HotSpotWorkloadMeasurementSettings(operations:Int=20,warmups:Int=40,paceMillis:Long=100)` validates1..600 operations,0..400 warmups,1..100ms pace. Existing4 fixture args preserved; optional5..7 specify these settings.

- [x] Red defaults/bounds tests, then implement pure settings.
- [x] Parameterize existing supported fixture. Record admission start, workload entry, warmup end, marked end, profile return/failure in memory; write reports only after profiling/cleanup.
- [x] Preserve successful value/evidence and rejected original causes/native errno/partial counters/cleanup ownership. Never convert rejected profiling into success.
- [x] Keep accepted20/40/100 case; add guarded diagnostic600/40/1 case accepting only checked success or retained checked rejection with actual collector retirement.
- [x] Run actual public matrix with both endpoint aliases `unix:///var/run/docker.sock`, `MW_HOTSPOT_NATIVE_ACCEPTANCE=true`, explicit verified asset directory, `:profiler:installDist :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest' -x :profiler:rootfulContainerTest`. Inspect JUnit counts; skipped matrix is insufficient.
- [x] Commit `test(profiler): retain dense interpreter profiling outcomes`.

### Task4: Matched evidence/decision

- [x] Run3 fresh-process asset measurements each in pinned interpreter/SerialGC and pinned normalJIT offline control, alternating modes on identical immutable assets. Fresh process does not establish cold filesystem cache.
- [x] Run3 matched replay measurements each mode on identical schema1projection. If profiling is needed, retain JFR-instrumented rows separately from uninstrumented timing.
- [x] Record all rows, medians, JVM/image/commit/manifest/input identity, byte counts, phase definitions and paced/dense outcomes in evidence document.
- [x] Select optimization by measured phase: hashes dominate; refine to reducing helper debug/symbol bytes first (99.81% of input), preserving full hashing before designing a new digest provider. Reader/mapper work stays separate.
- [x] Create separate selected-optimization plan; keep issue open until actual cost reduction/bound verified.
- [x] Correct stale design639–642 completion wording; update operator limits only as measurements justify.
- [x] Run `:profiler:test :profiler:checkKotlinAbi :profiler:checkLegacyAbi`, backlog/lint/whitespace checks and `./gradlew build`. Native suites only for actual native behavior changes.
- [ ] Commit `docs(profiler): record interpreter performance diagnosis`.

## Completion

Stage costs separated; measured validation/replay semantics unchanged; paced/dense reports have provenance and checked cleanup; budgets/capabilities/completeness unchanged; concrete optimization selected from evidence.
