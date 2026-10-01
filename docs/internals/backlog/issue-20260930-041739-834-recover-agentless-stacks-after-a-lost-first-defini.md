---
title: "Recover agentless stacks after a lost first definition"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260930-022737-485-preserve-structured-agentless-hotspot-event-eviden"
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/internal/capture/lookup.go"
  - "experiments/hotspot-agentless/internal/capture/lookup_test.go"
  - "experiments/hotspot-agentless/internal/capture/enrichment.go"
  - "experiments/hotspot-agentless/internal/capture/recovery_test.go"
  - "experiments/hotspot-agentless/internal/model/record.go"
  - "experiments/hotspot-agentless/tests/integration/recovery_test.go"
  - "experiments/hotspot-agentless/tests/integration/structured_test.go"
  - "experiments/hotspot-agentless/scripts/prepare-gadget.py"
  - "experiments/hotspot-agentless/scripts/run-demo.sh"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/patches/faults/drop-first-definition.patch"
  - "experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/recovery.go.txt"
  - "experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/recovery.go.txt"
  - "experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/recovery_test.go.txt"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/patches/README.md"
  - "experiments/hotspot-agentless/results/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Recover agentless stacks after a lost first definition

**Context:**
Implement the approved early correctness gate: lose the first genuine stack definition while its Gadget BPF LRU entry stays resident, recover subsequent observations without rewriting original unavailable results, and preserve existing pruning/buffer defaults.

**Needed:**
1. Reproduce the missing-definition poison using a test-only collector build; add bounded targeted recovery through the existing Gadget LRU and verify no wrong attribution.

**Evidence (2026-09-30):**
- Red: genuine dropped application definition, BPF reference reused by 194
  events; 156 unavailable and 38 cancellation outcomes (`build/capture.Q2kQsI/`).
- Green: one targeted invalidation; 39 original events remained unavailable
  and 153 subsequent pathA observations resolved (`build/capture.HhSHln/`).
- Stricter recovery-clock chronology repeat passed (`build/capture.g74KC5/`).
- `make verify`: PASS; bounded/aborted/replaced scans, late definitions,
  exact-task matching, cancellation, EPERM/EACCES and terminal-before-recovery.
- Existing lookup/pruning tests retained; no LRU or buffer sizing changes.
- This tests loss after symbolization/before cache insertion, using a stable
  interpreter-only call path. The broader transport/JIT/thread/runtime matrix
  and allocator-uniqueness audit remain separate gates.
- Keep open pending delivery review; no high-rate/dedup optimization performed.
- Final repeat (`build/capture.EaldAd/`): 400 terminal outcomes; 38 original
  dropped-reference events unchanged; one invalidation and 152 subsequent
  pathA operations resolved after the recovery clock; zero recovery errors.
- Normal capture (`build/capture.IX53CR/`), backlog validation and
  `./gradlew build`: PASS.

---
<!-- id: issue-20260930-041739-834-recover-agentless-stacks-after-a-lost-first-defini  file: issue-20260930-041739-834-recover-agentless-stacks-after-a-lost-first-defini.md -->
