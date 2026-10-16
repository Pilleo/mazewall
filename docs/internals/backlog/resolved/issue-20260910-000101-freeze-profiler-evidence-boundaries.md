---
title: "Freeze profiler evidence boundaries"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/ProfileObservation.kt"]
target_symbols: ["ProfileObservation", "StackDefinition", "SyscallAttribution", "ProfilingCoverage"]
open_questions: false
---

# Freeze profiler evidence boundaries

**Context:** Public observation, stack dictionary, syscall argument, coverage, and Tier E result
collections could retain mutable caller inputs.

**Needed:** Copy mutable collections on ingress and publish unmodifiable snapshots on egress.

**Resolution:** Observation variants now own arguments, paths, and flag evidence; stack definitions,
agent definitions, syscall attributions, collector drains, coverage diagnostics, and Tier E profiles
own immutable snapshots. Mutation regressions cover constructor inputs and returned collections.

---
<!-- id: issue-20260910-000101-freeze-profiler-evidence-boundaries  file: issue-20260910-000101-freeze-profiler-evidence-boundaries.md -->
