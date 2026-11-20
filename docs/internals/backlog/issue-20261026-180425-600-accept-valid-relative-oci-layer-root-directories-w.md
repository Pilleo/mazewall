---
title: "Accept valid relative OCI layer root directories without weakening path checks"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/worker-preparation-5/qualify.py"
target_symbols:
  - "Image.files"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Accept valid relative OCI layer root directories without weakening path checks

**Context:**
Actual Docker recovery attempt5 stopped at node-builder-qualification child exit1 on unsafe relative input. Local admitted Go1.27 first-layer proof identifies offset0 raw ./, directory type5, size0 normalized by tarfile to dot; this is a qualifier false positive. Reviewed local fix-r2 passes 11 methods/97 scenarios but is not guest-deployed and the retained archive is unqualified.

**Needed:**
1. Preserve the actual failure and local RED/GREEN/review evidence; keep this issue open until a separately implemented and reviewed honest managed read-only ownership/closure role requalifies the retained existing node-builder archive with all original checks. No stopped-production guard reuse or new runtime action is authorized by this issue.

## Actual failure and causal proof

- [Attempt5 report](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/docker-task-5-report.md), [raw result](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/attempt-5/result.json) and [complete 780-byte child log](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/docker-task5-artifacts/failure-log-tail.txt) bind actual `node-builder-qualification` PID182945 / child exit1: `Image.files` → `safe_relative(name)` → `ValueError: unsafe relative input`. The raw log did not print the offending member; preserve that historical limitation.
- The later [local admitted-base proof](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/checks/base-root-proof.json) identifies the first member at header offset0: raw `./` (`2e2f`), explicit directory type5, size0, presented by tarfile as `.`. Layer `sha256:8d0037029946d2c6dabce18eceda79c533c5797ed1ee86f94084cb8cddaeea91` has verified compressed size49393622 and expanded diffID `sha256:0fceba70669334d0c2890d8a9c5e8f0a8c153fde2d921de69084f3360e741c08`. The frozen qualifier requires that admitted Go1.27 layer/diffID prefix before the failing method; there is no earlier member. This establishes the false positive locally, without claiming a new guest inspection.
- Docker and fresh host24GiB/guest20GiB admission, exclusive new storage, controlled OCI and both independent worker stops passed. This failure is not a sudo/authentication handoff, ENOSPC or an observed compiler failure. Node-builder solve/probe predecessor completion is source-order evidence only; their raw numeric receipts were not collected. All five selected image exports remain unqualified.

## Reviewed correction is local only

[Latest local report](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/fix-r2/fix-r2-report.md), [independent Spec/Quality PASS](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/fix-r2/review.md), [sealed source manifest](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/fix-r2/manifest.json) and [review package](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/fix-r2/fix-r2-review-package.json) bind local qualifier SHA256 `db11cdd1e52efb73d889ea20bf35e9539245427940ae61f3d9e136de104e3e50`. The narrow root exception requires a physical explicit relative directory, independently checked raw name/type/prefix and physical/effective size0; it does not relax shared path validation. Traversal/absolute/link/nonzero/PAX-disguised roots still reject, diagnostics are bounded, and digest/diffID/selected-file/ELF/Go/compiler/production ownership checks remain.

The retained [initial RED](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/checks/root-red-assertions.json), [physical-size RED](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/fix-r2/checks/physical-size-red.json) and [final GREEN](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/fix-r2/checks/physical-size-green.json) establish TDD coverage: **11 methods /97 scenarios**, actual hostPython3.14 exit0. These tests were not rerun for this documentation task. Earlier fix levels and reviews remain immutable. GuestPython3.12 compatibility and actual existing-archive requalification are still unproved; the fix was not deployed or executed in the guest.

## Remaining acceptance gate; keep open

The [existing read-only proposal](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/oci-qualifier-fix/readonly-action-proposal.json) is **NOT_RUNNABLE**: `runnableSourcePrepared=false`, no honest managed read-only acquisition/release/wait/independent-closure adapter and no action approval. A new private namespace must not borrow or monkeypatch `guard_job('production')` from the stopped worker. Implement/review that distinct safety contract and reuse the complete pure qualifier checks before any bounded check of the fixed retained `node-builder.oci.tar`; do not start a new solve, allocate another filesystem, restart an old unit or fabricate unknown archive inode metadata.

This issue remains **open**, not archived from local tests or source review. [Current Docker outcome](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/rootful-docker-outcome.md) and [Task3 report](../../../.superpowers/sdd/2026-10-26-hotspot-offline-build-storage-recovery/task-3-docker-report.md) distinguish completed storage/closure gates from the unqualified artifact and later integration gates. No manual sudo/authentication setup or new operator permission handoff is needed to use the already-working Docker route. Captured issues395/019/431/520 and all historical failures/UNKNOWNs are unchanged.

---
<!-- id: issue-20261026-180425-600-accept-valid-relative-oci-layer-root-directories-w  file: issue-20261026-180425-600-accept-valid-relative-oci-layer-root-directories-w.md -->
