---
title: "Admit qualified observed HotSpot profile through actual CLI"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/capture/observed.go"
  - "profiler/hotspot-agentless/internal/capture/observed_candidate.go"
  - "profiler/hotspot-agentless/internal/capture/observed_test.go"
  - "profiler/hotspot-agentless/cmd/mw-hotspot-collector"
  - "experiments/hotspot-agentless/internal/capture"
  - "experiments/hotspot-agentless/internal/candidate"
  - "experiments/hotspot-agentless/cmd/mw-hotspot-demo"
  - "docs/plans/2026-10-19-hotspot-observed-admission.md"
  - "docs/plans/2026-10-18-hotspot-observed-profile-contract.md"
  - "docs/plans/2026-10-19-hotspot-integration-gate-checkpoint.md"
  - "profiler/hotspot-agentless/README.md"
target_symbols:
  - "RunObserved"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Admit qualified observed HotSpot profile through actual CLI

**Context:**
Operator explicitly approved scoped observed admission after six-case qualification and review at a7b6e878/d02d0854; strict behavior and policy ineligibility must remain unchanged. SDK publication and cluster operations are not authorized.

**Needed:**
1. Exercise public entry validation and actual CLI dispatch RED, share the qualified owned capture path, rebuild strict/observed bundles twice and execute actual CLI acceptance

## Resolution

Explicit operator approval is recorded in the
[admission evidence](../../../plans/2026-10-19-hotspot-observed-admission.md).
Public observed entrypoints now validate and share the previously qualified owned
implementation; both CLIs dispatch schema3 there. Host RED/GREEN and both full
owner race suites pass. Actual default CLI captures return400/400pairs with
380/384/380useful exact prefixes for ordinary/churn/unload; tagged negative controls
preserve failed captures and original causes. Both complete locked-Go bundles
reproduce byte/path/mode exactly. Strict public-native acceptance executes8tests
without skips/failures; the full root gate passes in6m58s. Strict2 and false policy
eligibility are unchanged. No SDK publication, external mutation or cluster action.

Historical execution targets remain available in the immutable original
[provider plan](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/plans/2026-10-18-hotspot-observed-provider.md)
and [implementation entrypoint](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/plans/2026-10-18-kubescape-agentless-hotspot.md).
Their removal from the shipping tree does not alter this resolution or its receipts.

---
<!-- id: issue-20261019-064719-782-admit-qualified-observed-hotspot-profile-through-a  file: issue-20261019-064719-782-admit-qualified-observed-hotspot-profile-through-a.md -->
