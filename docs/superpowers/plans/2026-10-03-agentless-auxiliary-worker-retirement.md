---
document_type: execution_plan
base_revision: d96651b6
status: implemented_strict_auxiliary
---

# Owned auxiliary worker retirement

## Goal

Require acknowledged auxiliary callback/readout completion before strict full
event/native retirement without canceling definition or enrichment ownership.

## TDD cases

- Missing tracer stop capability must fail before implementation.
- A strict full report without auxiliary acknowledgment must be rejected.
- Held callbacks/timeouts must retain resources and sealed admission.
- Cancellation must preserve EPERM/EACCES and other unexpected read failures.
- Finalizers must run after worker joins; idle native netlink must wake safely.
- PID/native async admissions and lazy prune startup must be sealed at stop.

Scope: issue-20261003-132317-306, additive experiment dependency patches and
strict full stop coordination. No dependency/API break or new runtime profile.

1. Verify the missing tracer auxiliary stop boundary fails before implementing.
2. Add an opt-in context-owned worker scope: sealed admission, checked wake/close
   callbacks, cancellation and bounded join. Enroll periodic, PID, report-perf,
   kernel-symbol and native async-map workers while keeping definition readout
   and enrichment on their independent context.
3. Preserve native errors and make PID/async admission cancellation-aware. Add
   deterministic held callback/deadline, post-stop admission, permission error,
   definition-lifetime isolation and backend ordering regressions.
4. Stop/join auxiliary work after full enrichment and before event resources
   retire, under the existing shared deadline. Resolver Close must enforce the
   same gate; unjoined work retains resources.
5. Verify host/native/live strict and legacy controls, record exact limits and
   provenance, run merge/backlog checks and commit only scoped files/index rows.

Impact tools are PARTIAL/CODANNA_UNAVAILABLE. Use direct pinned source/caller
evidence for actual enrollment and retirement paths. Existing characterizations
establish that ticker stop/context cancellation alone does not join callbacks.

## Execution evidence

The missing tracer boundary and unjoined-report regressions failed before the
fix. Scope enrollment covers periodic/PID/report-perf/kernel-symbol/processmanager
and async native update workers, with sealed lazy Gadget pruning. Held callbacks
retain leases under timeout; post-join socket finalizers preserve native errors.
Canceled read permissions were separately reproduced red and preserved green.
A live netlink-close rejection led to typed deadline wake and post-join retirement.
Host/race, native perf/netlink, definition8/5000 and event retention gates pass.
Final three1200-token full repeats, strict unloading, genuine recovery and legacy
capture pass. Fresh reporter construction prevents the stale executable from
omitting auxiliary completion. Exact provenance and limits are recorded in
`experiments/hotspot-agentless/results/auxiliary-worker-retirement.md`.
