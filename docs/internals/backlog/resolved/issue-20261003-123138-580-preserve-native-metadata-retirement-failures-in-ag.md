---
title: "Preserve native metadata retirement failures in agentless drain health"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
target_symbols:
 - "ProcessedUntil"
  - "ProcessedUntilChecked"
  - "WaitTraceReader"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve native metadata retirement failures in agentless drain health

**Context:**
Pinned ProcessManager.ProcessedUntil logs interpreter Detach failures, wraps causes with percent-v, and removes ownership entries; strict drain cannot observe EPERM/EACCES from this path.

**Needed:**
1. Add an additive checked metadata watermark boundary with fault-injected Detach tests preserving errno and retained failed ownership, then wire strict drain without breaking the existing API.

**Source evidence:** In the pinned profiler revision `ef6cf29492c8`,
`processmanager/processinfo.go:751-784`, `ProcessedUntil` returns no error.
Interpreter `Detach` errors are joined into a local variable using `%v`, logged
by a defer, and the interpreter/exit-event ownership entries are deleted anyway.
The selected generated bundle retains this code. The strict definition reader
calls this boundary in `tracer/events.go:296,299`; its drain error control cannot
observe these failures. A consumed perf flush therefore does not establish
successful metadata retirement or preserve these native errno causes.

Scope the remedy to the additive experiment dependency patches. Preserve existing
upstream API compatibility, normal watermark ordering and successful detach
behavior. Test both EPERM/EACCES and partial per-instance failure without closing
or deleting failed ownership. A fault reproduction is required before claiming a
fix. Existing live drain results are transport evidence, not this fault proof.

**Resolution evidence (2026-10-03):**
The original void API delegates to an additive checked boundary. Failed instances,
their process-info owner and exit watermark are retained; successfully detached
instances are removed individually and never detached again on retry. Errors use
`%w`/`errors.Join`, with strict fatal delivery after unlocking the metadata mutex.
Both original reader watermark branches use the checked boundary, retain its
error in reader control and stop instead of claiming a flush. Reader joins return
stored failures for both ordinary cancellation and explicit full drains.

Red tests observed hidden EPERM/EACCES and a joined reader returning nil despite
stored native failures. Green race tests cover both errno identities, mixed
successful/failed ownership, watermark ordering, process-info retention, explicit
retry and unlocked fatal callback. Host experiment verification, native 8/5000
definition tails and event-finalization controls pass. Three 1200-token live
strict repeats and strict unloading pass on the initial checked build; the final
reader-join build passes a 200-token live control. Full merge gate passed after
an unrelated intermittent JVM integration failure and unchanged focused retry.
See [`metadata-retirement.md`](../../../../experiments/hotspot-agentless/results/metadata-retirement.md).

---
<!-- id: issue-20261003-123138-580-preserve-native-metadata-retirement-failures-in-ag  file: issue-20261003-123138-580-preserve-native-metadata-retirement-failures-in-ag.md -->
