---
title: "Qualify owned Koog tools inside deterministic real agent loops"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files: 
  - "tests/consumers/koog-portal/build.gradle.kts"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify owned Koog tools inside deterministic real agent loops

**Context:**
Approved inline Plan B: qualify actual Koog1.3.0 AIAgent loops using a bounded scripted PromptExecutor, real published adapters, separate fresh thread/Portal JVMs. Operator approved consumer-only agents-core1.3.0 dependency. No live providers, adapter dependency changes, fallback/replay, raised budgets or core changes.

**Needed:**
1. B1 add consumer-only dependency and bounded success/validation agent probes; B2 qualify denial/overload/cancellation/deadline/terminal unknown outcomes and require exact per-mode evidence in existing optional acceptance. Native inline with one final independent review.

## Inline execution

- [ ] B1 scripted actual-agent consumer probes and reviewed dependency state.
- [ ] B2 failure/lifetime qualification and mandatory parent evidence gate.
- [ ] Exact-source acceptance, full build and one independent final review.

Pre-flight: B2 consumes B1's two fresh-JVM tasks and assertion-bound summaries;
neither plan changes the published Koog adapter or shares Spring interfaces.
Consumer-only `ai.koog:agents-core:1.3.0` is explicitly approved by the operator.
No plan files, separate journals or worktrees.

---
<!-- id: issue-20261025-223944-611-qualify-owned-koog-tools-inside-deterministic-real  file: issue-20261025-223944-611-qualify-owned-koog-tools-inside-deterministic-real.md -->
