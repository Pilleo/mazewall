---
title: "Tier E WP-09: LiveEbpfCollector integration into :profiler"
severity: "ENHANCEMENT"
status: "open"
priority: high
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/collector/EbpfCollector.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfileObservation.kt"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023938-tier-e-wp-08-ffm-bridge-client.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-09 — LiveEbpfCollector Integration

**Context:** The integration point was pre-carved by the existing collector architecture.
`ProfileCollector` is strategy-neutral with the KDoc contract *"Implementations must not compile
policies themselves"* — invariant 8 is already a type-level rule. `EbpfCollector` today is an
explicit stub ("live attach is not implemented; would need a privileged sidecar"). Tier E **is**
that sidecar.

Design reference: [tier-e-design.md §4.4, §10](../../designs/profiler/tier-e-design.md).

**Needed:**

1. Implement live attach behind the existing `ProfileCollector` interface (new
   `LiveEbpfCollector`, or activate `EbpfCollector.liveAttach=true` — implementer's choice after
   reading the stub's failure semantics).
2. Translate ring-buffer records → `ProfileObservation.Syscall`, extending the model with:
   * `contextId: ContextId?` (null ⇒ UNKNOWN semantics preserved),
   * `attribution: AttributionKind`.
   Extend here, not earlier — the shape must be informed by real events, not speculation.
3. Map drop counters → `CollectorDrain.droppedEvents`; any drop ⇒ `drainComplete = false`
   (invariant 9). Merge behavior already exists in `ObservationMerger`.
4. Gate Gradle wiring of `ebpf-prototype/` build outputs as required for tests only; production
   artifact packaging is a separate decision surfaced in the PR.
5. **Do NOT add any policy-compilation path.** If you find yourself writing `toPolicy()`,
   stop — that function must not exist (invariant 8).

### Tests

```text
unit: record→observation translation incl. UNKNOWN/drop mapping (mocked engine)
integration (rootful): profile a workload via LiveEbpfCollector; drains merge with StraceCollector cleanly per CollectorHybridTest semantics
```
