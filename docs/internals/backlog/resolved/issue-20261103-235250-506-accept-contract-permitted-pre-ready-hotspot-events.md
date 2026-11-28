---
title: "Accept contract-permitted pre-ready HotSpot events in the shared Go decoder"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "hotspot-bridge/wire/decoder.go"
target_symbols:
  - "Decoder"
open_questions: false
---

# 🟡 [Severity: HIGH]: Accept contract-permitted pre-ready HotSpot events in the shared Go decoder

**Context:**
Repository-wide review, 2026-11-04: [HS09-01](../../reviews/2026-11-04-repository-review-findings.md#hs09-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · correctness/availability · reproduced, high confidence · optional bridge/HotSpot product integration release. NEW.**

- **Trigger/evidence:** `profiler/hotspot-agentless/README.md:170–178` explicitly permits events from running target threads before ready. `docs/plans/2026-10-04-mazewall-hotspot-protocol-milestone.md:11–21` also documents native reader/receiver/program startup before the priority-100 ready callback and the permitted early events. The collector subscribes and submits event callbacks at `internal/capture/collector.go:254–279`; ready is published from the subsequent start callback at `287–335`. Kotlin accepts payloads after session start while still awaiting ready (`HotSpotSessionInterpreter.kt:34–53`). But Go `hotspot-bridge/wire/decoder.go:158–162` rejects every event/enrichment/recovery unless the phase is already `collecting`, which is set only at ready (`147–156`).
- **Reproduction:** The existing observed fixture succeeds in its original order. Feeding the same rows as `session_started, event, enrichment, collector_ready, session_summary` gives Go `row 2 (event): invalid session order: event before ready`; the exact current Kotlin interpreter reaches `Completed`. This is protocol replay, not a claim that a new native scheduling experiment ran.
- **Effect:** Attachment to an already active JVM can fail merely because valid background activity reaches the pipe before the ready row. A later good ready/summary cannot repair the sticky decoder failure. Dropping the early rows would also break final counters and occurrence evidence.
- **Smallest complete fix:** Admit bounded, independently target/session/provider-validated occurrence traffic after start while still withholding Ready. Require actual validated ready before accepting a successful final summary. Preserve event-before-terminal ordering and all existing retention/accounting limits; do not gate or discard genuine native events to satisfy the consumer.
- **Regression/qualification:** One shared strict2/observed3 corpus with start/event/terminal/ready/summary, including early unavailable/recovery and failure-before-ready cases. Keep no-ready finalization and terminal-before-original-event negative controls. Ask the native lane to retain or check an actual active-target ordering case when its already-authorized checks permit; no new native run is authorized here.
- **Mapping/dependencies:** New compatibility defect against resolved readiness milestone `issue-20261004-131726-750...`; foundational gate for `issue-20261019-071745-155...` SDK/provider release and the two-consumer integration. Independent of broader JIT lifetime/all-source-loss work.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

The Go reader admits validated occurrence rows after start while retaining the independent ready requirement for summaries. The regression failed in both schemas before this change and passes afterward. The complete hotspot-bridge module passed go test -race -shuffle=on -count=1 ./....

---
<!-- id: issue-20261103-235250-506-accept-contract-permitted-pre-ready-hotspot-events  file: issue-20261103-235250-506-accept-contract-permitted-pre-ready-hotspot-events.md -->
