---
title: "Retain ambiguous benchmark container acquisition ownership"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/benchmark/runner.go"
target_symbols:
  - "runOne"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain ambiguous benchmark container acquisition ownership

**Context:**
Connected HotSpot remediation review finds target docker run -d and collector create clear their receipt strings on client errors (runner.go lines 192-195 and 234-237), without named/session-labelled recovery requests. A remote success after client timeout is therefore not represented by the shared known-ID retirement helper. Candidate acquisition is fixed separately; benchmark acquisition still needs its own scoped change.

**Needed:**
1. Use actual receipts and qualified name/session recovery for benchmark acquisition, retain unknown outcomes and original failures, and inject late-create controls before claiming remote retirement.

---
<!-- id: issue-20261016-225418-065-retain-ambiguous-benchmark-container-acquisition-o  file: issue-20261016-225418-065-retain-ambiguous-benchmark-container-acquisition-o.md -->
