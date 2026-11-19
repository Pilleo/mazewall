---
title: "Bound total HotSpot line-table decoding and refill allocation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/hotspot-agentless/patches/profiler-epoch-memory.patch"
target_symbols:
  - "getMethodMemory"
open_questions: false
---

# 🟡 [Severity: HIGH]: Bound total HotSpot line-table decoding and refill allocation

**Context:**
Repository-wide review, 2026-11-04: [HS-01](../reviews/2026-11-04-repository-review-findings.md#hs-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · reproduced decoder component + connected source defect · confidence high · optional profiler only.**

**Trigger:** a selected HotSpot Method/ConstMethod has the line-number-table flag set, but its readable table bytes contain no zero terminator—for example stale/corrupted or deliberately modified target memory returning repeated `0x01`. The method pointer and epoch can remain unchanged. No assertion is made that ordinary well-formed JVM metadata naturally takes this path.

**Connected evidence:**

- Locked upstream source is `github.com/inspektor-gadget/opentelemetry-ebpf-profiler@v0.0.202611-0.20260715114040-ef6cf29492c8`; see `profiler/hotspot-agentless/dependencies.lock.json:9–14`. Local reference root **UP** is `/home/leanid/go/pkg/mod/github.com/inspektor-gadget/opentelemetry-ebpf-profiler@v0.0.202611-0.20260715114040-ef6cf29492c8`.
- `UP/interpreter/hotspot/instance.go:340–377`: reads a line table with 256-byte refills until the decoder returns an error. There is no entry count, enclosing allocation extent, total byte budget or cancellation check in that loop.
- `UP/interpreter/hotspot/recordingreader.go:27–37`: every refill allocates `len(previous)+chunk` and copies the entire previous buffer. Retained bytes are linear; cumulative allocation/copy work is quadratic.
- `UP/interpreter/hotspot/unsigned5.go:61–84`: `0x01` is accepted repeatedly; only `0x00` terminates a short-entry stream.
- This code remains on the authored path: `profiler/hotspot-agentless/patches/profiler-epoch-memory.patch:52–63,94–101` forwards the selected reader into the same method/line-table implementation. `patches/profiler-additions/interpreter/hotspot/identity.go.txt:43–61` invokes it between pointer checks.
- `profiler/hotspot-agentless/patches/profiler-additions/interpreter/hotspot/observed.go.txt:29–43` limits **one** read to 1 MiB; every 256-byte refill passes. `epoch.go.txt:25–40,105–117,148–165` supplies error retention and before/after epoch validation, not a cumulative decode budget.
- The consumer invokes `HandleTrace` synchronously (`patches/gadget-definition-drain.patch:12–21`). Its native join waits on consumer completion or the deadline (`patches/gadget-additions/pkg/symbolizer/otel/definition_drain_native.go.txt:19–25`); cancellation of that wait cannot interrupt this decoder.

**Consequence:** a bad metadata region can consume disproportionate CPU/GC and memory, stall the definition consumer, and prevent checked retirement within the owned stop budget. Enrichment's 1,024-event/16-MiB queue bounds do not bound this separate native decoder. External process supervision can terminate a stuck collector; it does not make the decoder itself bounded. This is an availability/failed-session defect, not evidence of falsely complete coverage.

**Reproduction:** `08-line-table-repro/` contains byte-identical copies of the two upstream decoder files, verified against both the locked cached module and the existing prepared dependency. The bounded test accepted 262,144 entries, retained 262,144 bytes and made 1,024 reads; the literal refill sizes imply 134,348,800 bytes allocated and 134,086,656 bytes copied. It passed in 0.153s because its assertion demonstrates the defect, then deliberately stops. No OOM, live process-memory read or native attach was attempted. The stale prepared tree is corroboration only; the current authored patch and matching locked-base files establish reachability.

**Smallest complete correction:** bound total line-table bytes/entries against a justified supported maximum or independently validated ConstMethod extent, check cancellation at refill boundaries where available, and propagate bound exhaustion as unavailable/failure before caching partial metadata. A refill strategy that grows geometrically within that bound removes the quadratic copying. Merely adding a per-call read limit is insufficient. The current method loop suppresses non-EOF line-table errors, so a new local budget error must not silently produce a partially hashed/cached method.

**Regression:** exercise the real `getMethodMemory` path with an unterminated reader, valid boundary-sized tables and terminator-at-boundary cases; assert bounded reads/allocation, no partial cache insertion, retained EPERM/EACCES and prompt owned shutdown. Keep the finite component repro as the initial failing oracle for the corrected behavior.

**Mapping / dependency:** **NEW** after searching active/resolved backlog for line-table/RecordingReader/decoder-budget coverage. Related bounded-enrichment issue `issue-20260930-022737-485-preserve-structured-agentless-hotspot-event-eviden.md` does not cover this provider decoder. JIT/GC lifetime issue440 is a separate limitation. Put the new regression in the overlay gate described by HS-02; carry the bounded upstream change as its own maintenance unit.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235249-509-bound-total-hotspot-line-table-decoding-and-refill  file: issue-20261103-235249-509-bound-total-hotspot-line-table-decoding-and-refill.md -->
