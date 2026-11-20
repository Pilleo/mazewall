---
title: "Bind HotSpot workload oracle TIDs across PID namespaces"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "Corpus"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind HotSpot workload oracle TIDs across PID namespaces

**Context:**
The reused Java workload writes /proc/thread-self TIDs in its own proc/PID namespace, while collector events carry host TIDs. Host-only tests can hide this mismatch. Controlled PID1 pods require independently saved gated-worker status chains, not matching Java names or treating namespace IDs as global; proc comm truncation also makes worker names ambiguous.

**Needed:**
1. Validate the fixed 400-operation/four-worker oracle against bounded Pid/Tgid/NSpid/NStgid snapshots from independently verified host procfs, bracketed by raw process birth checks. Require four distinct target-bound namespace chains, zero wrong routes/TIDs/order, all400 marked pairs and at least360 useful correct routes; preserve unavailable terminals and reject missing/ambiguous/relative metadata without guessing.

**Local correction (2026-10-23):** Node-agent's offline `verify.Corpus` validates
the exact four-worker source oracle and maps namespace TIDs through bounded,
target-bound status chains. Its Linux verifier optionally accepts `--oracle`,
requiring workload validation after the original saved-profile check. An empty
explicit flag is rejected rather than silently downgrading to profile-only mode.
No procfs acquisition, Docker/cluster operation or native collection is performed.

**Regression evidence:** `/tmp/opencode/hotspot-corpus-red-20261023.log` reproduced
wrong-route/TID/order acceptance and the lowered useful denominator. A second
RED (`hotspot-corpus-scalar-red-20261023.log`) reproduced an empty status field
followed by its duplicate being accepted. Complete verifier race tests pass in
`/tmp/opencode/hotspot-corpus-green-20261023.log` (20.673s), including40 versus41
unavailable terminals and valid-wire truncated paths. CLI RED logs
`hotspot-corpus-cli-red-20261023.log` and
`hotspot-corpus-cli-empty-red-20261023.log` reproduce the missing flag and empty
flag downgrade; focused GREEN passes. The complete example race suite passes in
`/tmp/opencode/hotspot-corpus-cli-green-20261023.log` (CLI7.303s, verifier21.810s).
Scoped review then reproduced impossible intermediate namespace leaders/init IDs
and colliding worker TIDs (`hotspot-corpus-nested-red-20261023.log`); validation
now checks every namespace level, with a valid three-level chain as a control.
Final race checks pass in `/tmp/opencode/hotspot-corpus-final-20261023.log`
(CLI7.346s, verifier21.282s). Go vet, gofmt, local documentation link checks,
`git diff --check` and backlog validation pass for this slice. These are host
source checks in the labeled development workspace, not released/native proof.

**Outstanding acquisition requirement:** Workers must be independently selected
and snapshotted while gated, with raw process birth bracketing and independently
verified initial-namespace procfs origin. Linux man-pages6.19
[`proc_pid_status(5)`](https://man7.org/linux/man-pages/man5/proc_pid_status.5.html)
defines leftmost namespace IDs relative to the procfs mounter, not inherently
global. Field consistency cannot prove origin. Thread names and event TIDs must
not be used to invent this custody proof. Keep this item open until owned-runner
integration, separately authorized native/persisted-profile acceptance and review.

---
<!-- id: issue-20261022-230122-825-bind-hotspot-workload-oracle-tids-across-pid-names  file: issue-20261022-230122-825-bind-hotspot-workload-oracle-tids-across-pid-names.md -->
