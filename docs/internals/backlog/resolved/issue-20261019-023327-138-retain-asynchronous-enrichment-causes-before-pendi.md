---
title: "Retain asynchronous enrichment causes before pending readout returns"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/capture/enrichment.go"
  - "profiler/hotspot-agentless/internal/capture/collector.go"
  - "profiler/hotspot-agentless/internal/capture/enrichment_fatal_test.go"
  - "experiments/hotspot-agentless/internal/capture"
target_symbols:
  - "Enricher"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retain asynchronous enrichment causes before pending readout returns

**Context:**
Coupled same-PID JVM exec is independently proved, but enrichment cancels the session with its original cause stored only behind Finish; the unjoined native readout path returns cancellation without typed exec cause. Joined cancellation and permission causes also require fatal precedence.

**Needed:**
1. Reproduce joined-permission loss and a held terminal writer; forward original asynchronous causes to the bounded session fatal owner before blocking writes or joins while preserving the existing NewEnricher signature

Actual rejected coupled run: `build/kubescape-dev/coupled-exec/native-canonical/`.
The JVM becomes the independently resolved sleep executable with unchanged
PID/birth/cgroup/mount tuple, but the child reports only pending native readout
and cancellation. The session remains failed; no qualifier condition is relaxed.

## Resolution

The unchanged NewEnricher entry delegates to a private constructor with an
error-report callback. Collector sessions forward actual lookup/recovery causes
to the existing bounded fatal owner before taking the enrichment lock or writing
terminals/recovery output; sink causes are retained when observed. Joined native
EPERM/EACCES overrides ordinary cancellation handling. No blocking Finish,
invented native observation, budget increase or public API break is introduced.

Behavioral joined-permission RED, held-output and typed-cause controls pass after
the fix. Actual coupled JVM exec now reports its original typed poison, all six
candidate controls pass, and both owner Go/race suites pass. Rebuilt strict
public-native acceptance executes8tests/0skips/0failures; the full root build
passes in6m20s. [Complete evidence](../../../plans/2026-10-19-hotspot-coupled-exec-evidence.md).

---
<!-- id: issue-20261019-023327-138-retain-asynchronous-enrichment-causes-before-pendi  file: issue-20261019-023327-138-retain-asynchronous-enrichment-causes-before-pendi.md -->
