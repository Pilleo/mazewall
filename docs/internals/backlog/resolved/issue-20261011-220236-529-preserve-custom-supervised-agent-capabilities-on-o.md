---
title: "Preserve custom supervised agent capabilities on owned runtime migration"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: [enforcer, agent-sandbox-demo]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
target_symbols:
  - "ManagedRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve custom supervised agent capabilities on owned runtime migration

**Context:**
**Resolution — 2026-10-12:** Needed step1 fulfilled for supported early VFORK bootstrap: captured handlers, actual custom allowed/denied behavior and physical owner publication/close accepted. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-4a/task-4a-fix-review.md` and fix receipts (24 distinct agent/1 native), accepted P617 source gate. The observed unsupported FORK callback bypass is **not fixed** and remains [separately active](../issue-20261012-192946-226-track-unsupported-jdk-fork-launch-callback-bypass-.md). [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md). Earlier WIP/open prose is historical.

Task4a captures custom scoping authority before owned readiness and migrates agent lifecycle without changing approved network and process permissions.

**Needed:**
1. Capture handlers once on trusted caller and verify actual custom allowed and denied scenarios on owned workers.

## Implemented WIP (Task4a)

- One additive `ManagedRuntime.prepare(policy, limits, scopingPolicy)` captures handler membership once on the caller and retains the callbacks through existing owned generation installation/retirement. Existing signatures and borrowed APIs stay available for Task4b.
- Agent bootstrap explicitly prepares supervision before the launch-compatible process baseline; tools use bounded owned domains, invocation cancellation/physical end, and runtime drain. Unprotected comparison remains explicit.
- Synchronous HTTP connection stays on the actual web worker. Exact CONNECT and VFORK/FORK callbacks remain selected; no extra process prohibition or permission union is installed.
- Host capture/failure/zero-entry and real native custom-authority, agent allowed/denied, child cancellation/reap and worker retirement controls are retained in operator evidence: `/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/task-4a/task-4a-report.md` and `task-4a-review.diff` in the same directory.

## Discovered launch limitation

An actual JDK25 FORK-mode probe allowed the malicious web-stack shell without a custom spawn verdict. Linux glibc fork can use CLONE, which these exact demo policies deliberately allow; the failed fixture is evidence of uncovered launch, not a syscall trace. FORK handler presence does not prove JDK FORK-mode coverage. The protected scenario bootstrap now rejects an explicitly incompatible launch mode before tool construction; supported runs require VFORK selected before ProcessImpl initialization. The unchanged handlers still cover their actual VFORK/FORK syscalls. Detecting an already initialized ProcessImpl with a subsequently changed property is not supplied by a public JDK API; trusted early bootstrap and the existing application JVM argument remain prerequisites. No broad CLONE supervision or new baseline was introduced.

Scoped review and the later combined gate remain pending; keep this issue open.

## Fix round 1 — review I1/I2/M1

- I1: acquired-server assertions reproduce and correct error-body, declared charset, trickled-header overall response deadline and delayed-body compatibility. HTTP remains synchronous on the actual custom-supervised worker; the owned deadline observer only expires header acquisition. Both comparison and protected fixture modes run without external egress.
- I2: a closing/snapshot transition and post-publication closing check guarantee that each retained admitted handle is captured for cancellation or cancels itself on late publication. No tool monitor covers core submission/resource waits, execution, result waiting, cancellation or drain. The held-publication fixture first reproduced incomplete drain with one live admitted analysis, then proves cancellation and actual child/worker death after the fix.
- M1: the FORK fixture comment now distinguishes observed callback bypass from the possible, untraced CLONE explanation.
- The existing canonical report is append-only. `task-4a-fix.diff` in the same artifact directory records only this round versus the exact reviewed WIP; the complete Task4a diff/custody are refreshed. Re-review is pending.

---
<!-- id: issue-20261011-220236-529-preserve-custom-supervised-agent-capabilities-on-o  file: issue-20261011-220236-529-preserve-custom-supervised-agent-capabilities-on-o.md -->
