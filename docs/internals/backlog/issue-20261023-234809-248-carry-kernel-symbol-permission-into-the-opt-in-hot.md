---
title: "Carry kernel-symbol permission into the opt-in HotSpot node runner"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "/home/leanid/Documents/code/java/stack-attribution/node-agent/examples/agentless-hotspot-sbob/runner/run_linux.go"
target_symbols:
  - "startAgent"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Carry kernel-symbol permission into the opt-in HotSpot node runner

**Context:**
Planning after accepted native attempt4 found the current opt-in node-agent Pod requests BPF/PERFMON/SYS_ADMIN/SYS_PTRACE/SYS_RESOURCE/DAC_READ_SEARCH but omits SYSLOG. On the qualified kptr_restrict1 host the same collector needed CAP_SYSLOG to pass OTel kallsyms initialization. Direct-host native acceptance does not establish container effective capabilities; preserve non-root UID1000, baseline sensors, existing helper roles and fail-closed errors.

**Needed:**
1. Add a behavioral rendered-Pod regression for the exact seven-capability set and existing non-privileged/UID/mount constraints, then append only SYSLOG to startAgent's opt-in node-agent Pod. Verify on the actual released storage graph and qualify effective post-exec capabilities separately in the selected live environment; no default deployment or syscall/security relaxation.

**Evidence:** The current opt-in Pod construction at run_linux.go:1084-1086
requests six capabilities. Existing rendered-Pod coverage at
run_linux_test.go:1520 checks only DAC_READ_SEARCH. The source is unchanged by this
planning finding. Host attempt4's accepted result and all prior failures remain
in the [native outcome](../reviews/2026-10-23-hotspot-public-native-attempt.md).
Task4 of the [persisted-profile exit plan](../../superpowers/plans/2026-10-24-hotspot-persisted-profile-acceptance.md)
defines the narrow source/test correction; requesting a capability still does not
prove it is effective in the real non-root collector after exec.

**Source-request checkpoint (2026-10-24, live acceptance still open):**
The [partial persisted-profile handoff](../reviews/2026-10-24-hotspot-persisted-profile-acceptance.md)
records the reviewed SYSLOG-only correction on the actually resolved temporary
storage-fork graph. Exact behavioral RED on the original six-capability rendered
Pod preceded GREEN on seven; full runner/cmd-run races, vet and corrected runner
build reached exit0. The Add set is BPF/PERFMON/SYS_ADMIN/SYS_PTRACE/SYS_RESOURCE/
DAC_READ_SEARCH/SYSLOG; Drop ALL, nonprivileged Pod, UID/GID1000, mounts, baseline,
archive, flags and fail-closed errors are unchanged. The source is no longer missing
SYSLOG; the earlier source inspection above remains dated history.

Keep **open**: a capability request is not actual effective post-exec authority.
SYSLOG includes broader kernel-log/address authority; neither BPF nor target-memory
permission is established by host tests. Task5 actual matching images/dedicated
direct-node/socket/initial-proc/original-trust gate is **BLOCKED**; Tasks6–7 current
API/persisted positive/recreation/controls are **NOT_RUN**, with null exits. No
security/live/native grant, default deployment change or issue closure follows.

---
<!-- id: issue-20261023-234809-248-carry-kernel-symbol-permission-into-the-opt-in-hot  file: issue-20261023-234809-248-carry-kernel-symbol-permission-into-the-opt-in-hot.md -->
