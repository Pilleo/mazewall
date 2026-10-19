---
title: "Run integration-boundary host controls from the standard check task"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "benchmarks"
target_modules: []
target_files:
   - "benchmarks/integration-boundaries/build.gradle"
   - "benchmarks/integration-boundaries/README.md"
   - "benchmarks/integration-boundaries/locks/0.1.0-thread-task3.c1bfd8a12b61.288b844cceb6.lockfile"
target_symbols: []
open_questions: false
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
has_side_effects: false
---

# 🟡 [Severity: LOW]: Run integration-boundary host controls from the standard check task

**Context:**
The standalone published-artifact benchmark has valuable existing --selfcheck and --selectioncheck entry points, but its standard check task does not execute them. Wire those existing host-only controls into check without adding a benchmark framework, native run, dependencies, performance thresholds or changing historical P617 locks.

**Needed:**
1. Prove with the actual standalone build and a real existing published candidate that baseline check succeeds without running either control.
2. Add two lazy JavaExec verification tasks for the existing CLI control flags and make check depend on them; retain JDK25 and published-only runtime closure.
3. Require actual selfcheck and selectioncontrols PASS markers and task execution, verify strict publication closure and configuration-cache reuse, and document host-only reproduction separately from native benchmark acceptance.

## Verified local implementation

Before the build edit, actual standalone `check` succeeded without executing
either control; the independent task/marker assertion failed for exactly that
reason. Afterward two JavaExec tasks run the existing entry points with Java25
and finite one-minute task timeouts, and `check` depends on both. No control code
or dependency declaration was duplicated/changed.

Strict exact-seven-module publication closure passes separately with cache
disabled, respecting that existing verifier's limitation. Standard `check`,
configuration-cache reuse, the exact extracted README recipe and the complete
standalone `build` all pass; both control bodies execute, including on cache
reuse. Acceptance commands do not regenerate locks. The new coordinate-specific
lock is only for the already-existing thread-task3 publication; historical P617
lock/source bytes are unchanged. Independent review found no actionable defects.

Evidence: `.superpowers/sdd/2026-10-19-benchmark-check-wiring/`, including real RED,
strict closure, cache logs and `complete-build.log`. This is not a native or
performance qualification. The separate root analyzer correction and full optional
build have since passed; see
`.superpowers/sdd/2026-10-19-strict-analyzer-repair/completion-receipt.json`.
Keep open for scoped integration.

---
<!-- id: issue-20261019-073259-804-run-integration-boundary-host-controls-from-the-st  file: issue-20261019-073259-804-run-integration-boundary-host-controls-from-the-st.md -->
