---
title: "Revalidate handed-off live recovery graph before writer authority consumption"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_LiveExecution"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Revalidate handed-off live recovery graph before writer authority consumption

**Context:**
The bounded-persistence implementer checkpoint reports that capture validates the recovery graph, but the live writer must validate the actual handed-off graph again before consuming RecoveryAuthority. A mutation between capture and write must not bypass structural validation. The focused matrix is still pending; a dedicated mutation regression and correction are required, and existing passing counts do not cover that correction. See .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/live-recovery-persistence-implementation.md, Remaining gates.

**Needed:**
1. Add the handed-off graph mutation regression, fund validator scratch before the owned resolve validation, reject before authority consumption or output effects, and retain RED/GREEN and same-invocation ownership evidence.

**Evidence state at filing:**
The implementer's in-progress report identifies the correction; the dedicated
mutation regression and final covering result are still pending. The reported
49 connected and14 diagnostic passes do not cover the forthcoming correction.
Keep this issue open until its regression, fix, same-revision checks and required
review evidence are recorded. Filing is not a completed independent review.

**Implementation and scoped review evidence:**
The completed persistence packet retains mutation RED in
`live-recovery-handoff-red.log`: the earlier writer consumed disposable authority
for the mutated handoff. Corrected GREEN rejects that actual handoff before
encoding, authority consumption or output, with validator scratch funded first.
The corrected revision passed5 cold checks,15 remaining checks and63 connected
checks. No prior-revision count is used as evidence for the correction.

Authoritative source copies, packet diff, logs, commands and result manifest:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-pye8dilh/`. Final entry SHA256:
`8fc1863f4aa463702d48156b6ff82cc8e80c273623b9c839f4c33aaf5f002fae`;
test SHA256:
`b6ff2560061be31d341f46400474026a80d5a60b9de4b9016ebe4574dd2cada2`.
The implementation report is
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/live-recovery-persistence-implementation.md`.
The independent scoped persistence review verified this exact correction:
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/live-recovery-persistence-review.md`,
"Issue 424 and other checked contracts", reviewed source pins above. The actual
handoff is validated before encoding or authority consumption, with funded
scratch and meaningful mutation RED/GREEN evidence. This resolves only the
handed-off graph defect. The review's separate capture findings are tracked by
issues20261103-090101-148 and20261103-090101-565; the overall packet still requires
their corrections. Neither this closure nor the scoped review qualifies the
native runtime or replaces the blocked earlier provider review.

---
<!-- id: issue-20261103-080737-424-revalidate-handed-off-live-recovery-graph-before-w  file: issue-20261103-080737-424-revalidate-handed-off-live-recovery-graph-before-w.md -->
