---
title: "Preserve structured agentless HotSpot event evidence with bounded enrichment"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260930-020717-934-capture-event-triggered-hotspot-openat-stacks-with"
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/internal/capture/ig.go"
  - "experiments/hotspot-agentless/internal/capture/collector.go"
  - "experiments/hotspot-agentless/internal/capture/decode.go"
  - "experiments/hotspot-agentless/internal/capture/decode_test.go"
  - "experiments/hotspot-agentless/internal/capture/enrichment.go"
  - "experiments/hotspot-agentless/internal/capture/enrichment_test.go"
  - "experiments/hotspot-agentless/internal/capture/lookup.go"
  - "experiments/hotspot-agentless/internal/capture/lookup_test.go"
  - "experiments/hotspot-agentless/internal/capture/lifecycle.go"
  - "experiments/hotspot-agentless/internal/capture/lifecycle_test.go"
  - "experiments/hotspot-agentless/internal/model/event.go"
  - "experiments/hotspot-agentless/internal/model/stack.go"
  - "experiments/hotspot-agentless/internal/model/stack_test.go"
  - "experiments/hotspot-agentless/internal/model/record.go"
  - "experiments/hotspot-agentless/internal/output/jsonl.go"
  - "experiments/hotspot-agentless/cmd/mw-hotspot-demo/main.go"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/internal/tooling/prepare.go"
  - "experiments/hotspot-agentless/scripts/run-demo.sh"
  - "experiments/hotspot-agentless/tests/integration/structured_test.go"
  - "experiments/hotspot-agentless/gadgets/openat-stack/program.bpf.c"
  - "experiments/hotspot-agentless/gadgets/openat-stack/gadget.yaml"
  - "experiments/hotspot-agentless/patches/gadget-structured.patch"
  - "experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/structured.go.txt"
  - "experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/structured.go.txt"
  - "experiments/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/structured_test.go.txt"
  - "experiments/hotspot-agentless/patches/README.md"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve structured agentless HotSpot event evidence with bounded enrichment

**Context:**
Implement Task 2 of the approved standalone plan on fast-master: expose Gadget's existing resolver frames, use bounded event enrichment and absolute deadlines, preserve unavailable results without stack substitution.

**Needed:**
1. Add a reproducible scoped Gadget structured-result patch, typed JSONL evidence and loss/reordering/cancellation tests; validate a genuine live Java capture.

**Verification evidence (2026-09-30):**
- `make verify`: PASS including original Gadget lookup/pruning tests and new
  shared-waiter, identity, deadline, immutable-frame, byte/count pressure tests.
- Genuine typed capture: 400 event records and 400 terminal outcomes, including
  explicit missing references and target-exit cancellation records.
- `--max-pending 1` live refusal check: all 400 events retained; 141 terminal
  pending-limit skips; maximum pending count exactly one.
- Patch is local and reproducible from checksum-verified sources; no upstream
  submission or acceptance is claimed.
- Kernel loss and BCI availability remain unknown. First-definition recovery
  must pass before deduplication optimization or high-rate evaluation.
- Digest-pinned container build and `./gradlew build`: PASS; a fresh live
  repeat of the container-built binary passed in `build/capture.xLLh1Y/`.
- Keep open until delivery review; do not archive from source inspection alone.

---
<!-- id: issue-20260930-022737-485-preserve-structured-agentless-hotspot-event-eviden  file: issue-20260930-022737-485-preserve-structured-agentless-hotspot-event-eviden.md -->
