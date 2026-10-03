---
title: "Preserve native metadata retirement failures in agentless drain health"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless"
target_symbols:
  - "ProcessedUntil"
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

---
<!-- id: issue-20261003-123138-580-preserve-native-metadata-retirement-failures-in-ag  file: issue-20261003-123138-580-preserve-native-metadata-retirement-failures-in-ag.md -->
