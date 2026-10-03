---
title: "Join agentless auxiliary profiler monitors before native retirement"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
target_symbols:
  - "StartMapMonitors"
open_questions: false
---

# 🟡 [Severity: HIGH]: Join agentless auxiliary profiler monitors before native retirement

**Context:**
Strict definition drain joins only reader/consumer; resolver Close can retire tracer resources while PID/map/metric callbacks remain on the active owned context. Periodic stop functions only stop tickers and do not join callbacks; PID sends are not cancellation-aware.

**Needed:**
1. Characterize held callbacks and post-stop manual triggers, then add owned auxiliary cancellation and joins under the remaining stop budget before tracer native close without interrupting definition drain or enrichment.

**Verified evidence (2026-10-03):**
The pinned profiler's `periodiccaller.StartWithManualTrigger` returns only
`ticker.Stop`. A manual trigger still invokes its callback after this stop returns;
context cancellation and ticker stop return while an already-entered callback is
held. Race-enabled characterization tests prove both behaviors using explicit
entry/release channels, not sleeps.

`Tracer.StartMapMonitors` discards these stop handles and uses an unconditional
`t.pidEvents <- pidTid` inside the PID callback. `StartPIDEventProcessor` starts an
unjoined goroutine. Strict resolver `Close` delegates to the completed definition
owner, without invoking `definitionCancel` on that branch, and then invokes tracer
Close. Existing joins do not include PID/map/metric callbacks, report-event perf
readout, process-manager metric collection or the kernel-symbol monitor.

This is a cancellation/join gap, not proof of complete native-resource retirement
or an observed live use-after-close. Define separate auxiliary ownership, stop
admission/cancellation-aware PID sends and join acknowledgments. Keep definition
transport and enrichment leases alive through their final handoff. Retain resources
on an unjoined callback under the remaining deadline; preserve default API behavior
and native errors. See
[`auxiliary-monitor-ownership.md`](../../../experiments/hotspot-agentless/results/auxiliary-monitor-ownership.md).

---
<!-- id: issue-20261003-132317-306-join-agentless-auxiliary-profiler-monitors-before-  file: issue-20261003-132317-306-join-agentless-auxiliary-profiler-monitors-before-.md -->
