---
title: "Expose observed HotSpot scope and BCI quality in agentless frames"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/patches/**"
  - "experiments/hotspot-agentless/internal/model/**"
  - "experiments/hotspot-agentless/internal/quality/**"
  - "experiments/hotspot-agentless/internal/capture/lookup.go"
  - "experiments/hotspot-agentless/internal/tooling/prepare.go"
  - "experiments/hotspot-agentless/scripts/run-demo.sh"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/tests/integration/quality_test.go"
  - "experiments/hotspot-agentless/tests/integration/structured_test.go"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
  - "docs/plans/2026-09-30-agentless-hotspot-quality-plan.md"
  - "docs/internals/backlog/README.md"
  - "docs/internals/backlog/issue-20260930-220901-114-validate-captured-interpreted-hotspot-method-ident.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Expose observed HotSpot scope and BCI quality in agentless frames

**Context:**
Expose executed provider decisions without confusing fallback zero with observed
BCI zero or successful PC selection with complete stack/inline fidelity. The
operator approved a comparable, immutable public frame metadata extension.

**Needed:**
1. Preserve exact/preceding/method-only/interpreter/stub provenance, explicit BCI
   states, raw inputs and partial scope-walk diagnostics through existing caches.
2. Validate checked scope descriptors, unsigned5 decoding, method indexes, null
   pointers and compiled-cookie mismatch; keep the original valid selection rule.
3. Verify normalization, typed cache propagation, live tiered/Xint delivery and
   lost-definition recovery; keep collection-loss observations partial.

**Implemented locally; review pending:**
`make verify`, native LRU/pruning and live quality/recovery gates passed. The old
capture failed the new quality gate specifically for missing provider metadata.
Container-built quality capture: `build/capture.YIUz6W/`; 400 terminals, 398
resolved, two unavailable, 1,269 exact-PC and 277 method-only frame observations.
BCI observations remain range-checked provider evidence, not an opcode oracle.
Backlog validation and `./gradlew build` passed after focused acceptance checks.

**Known limitations:** the pinned interpreted method cache ignores the captured
check argument (separate linked follow-up in the backlog index). The upstream
process manager logs symbolization failures and appends an unknown placeholder;
raw errors on retained partial frames are exported, but failures before emitting
a frame cannot be reconstructed as per-event decoder errors. No complete inline,
incarnation, loss-finality or overhead guarantee is made.

---
<!-- id: issue-20260930-212335-548-expose-observed-hotspot-scope-and-bci-quality-in-a  file: issue-20260930-212335-548-expose-observed-hotspot-scope-and-bci-quality-in-a.md -->
