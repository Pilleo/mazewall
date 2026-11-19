---
title: "Isolate cross-process signals in the required Portal worker baseline"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: [":portals:worker", ":enforcer"]
target_files:
  - "portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
target_symbols:
  - "PortalWorkerMain"
open_questions: false
---

# 🟡 [Severity: HIGH]: Isolate cross-process signals in the required Portal worker baseline

**Context:**
Repository-wide review, 2026-11-04: [PRT-01](../reviews/2026-11-04-repository-review-findings.md#prt-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · security · NEW · high confidence in source omission; kernel consequence not reproduced · release boundary: Portal worker baseline**

**Trigger:** A compromised worker runs under the ordinary same-UID deployment, with no additional operator-imposed signal restriction, and invokes a signal syscall targeting its parent or another same-UID service process. Native guest execution suffices; this does not require creating a process, opening a host path, or obtaining a network socket.

**Connected evidence:**

- `portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt:212–235`: required static policy is exactly filesystem allowlisting, process-creation denial, and network denial.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/api/ProcessPolicies.kt:15–40,51–57`: none of those policies restricts `kill`/thread-directed or queued signal authority. `PolicyBuilder.kt:29–31` defaults to ALLOW; `ProcessPolicyBuilder.kt:56` and `WorkerPolicyBuilder.kt:96` use that deny-list configuration.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt:71–80,142–175`: the inspection pipeline contains mmap/clone/prctl/socket inspectors, no signal-target restriction, and ends in the policy default action.
- `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt:657–665,688–697`: both ruleset constructors explicitly set `scoped = 0`.
- `platform/src/main/kotlin/io/mazewall/platform/process/ProcessLauncher.kt:46–58` starts an ordinary `ProcessBuilder` child; Portal does not establish a separate UID boundary.
- Linux documents signal scoping as opt-in `LANDLOCK_SCOPE_SIGNAL`, separate from Landlock's implicit ptrace restrictions: <https://docs.kernel.org/userspace-api/landlock.html#ipc-scoping>. The repository UAPI snapshot confirms the flag and ABI-6 introduction at `docs/internals/research/uapi-snapshot/linux/landlock.h:368–384`. Portal already requires ABI 8 for all-thread filesystem confinement.

**Effect:** Under those normal credentials, the advertised process split does not prevent the worker from signaling the broker or another isolation group. Ordinary Linux signal permissions allow damaging signals in that scenario. Source and the kernel contract support this authority gap; this review did not send a signal or demonstrate a killed/stopped host. **This finding makes no parent-memory/ptrace bypass claim.**

**Smallest complete correction:** Make signal-domain isolation part of the dedicated Portal baseline, using the supported Landlock scope or an equivalently complete target restriction; include it in required readiness/policy identity and regression coverage. Preserve legal JVM/self-thread signals. Do not globally deny JVM signal coordination or silently change unrelated general-purpose policy semantics.

**Qualification:** In an isolated disposable host/worker fixture, install the actual static baseline and attempt a safely handled signal toward the acquired host PID and another acquired sibling-group PID. Require denial and no delivery; prove same-worker signaling, GC, interruption, and normal RPC still work. Include an unsandboxed positive control proving the signal fixture can deliver. No production PID or destructive signal is needed.

**Mapping/dependencies:** Searches across active and resolved backlog for signal scoping, `LANDLOCK_SCOPE`, `tgkill`, queued signals, and cross-process IPC found no matching issue. Coordinate with lane 02; this is a new baseline defect, not P09 restart-budget work. Resolve/qualify before claiming compromised-worker isolation in P13. **ESCALATION REQUIRED:** policy/native owner confirmation and actual-kernel qualification; see requests below.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235252-762-isolate-cross-process-signals-in-the-required-port  file: issue-20261103-235252-762-isolate-cross-process-signals-in-the-required-port.md -->
