---
title: "Bind SFTP host source device to selected data filesystem"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/preparation-1/handoff.py"
target_symbols:
  - "validate_common"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind SFTP host source device to selected data filesystem

**Context:**
Final outcome review R1 confirms hardcoded sourceDevice2049 rejects the selected mount50 data filesystem1792; root/tool executable device2049 must remain distinct. Consumed batch BOUNDED_STOP/stress UNKNOWN; incomplete and seed NOT_RUN.

**Needed:**
1. In a separately selected offline change, bind source device1792 alongside mount50 and add realistic data1792/executable2049 acceptance plus internally consistent wrong-source2049 rejection regressions; preserve all other gates and consumed evidence.

## Evidence and scope

- [Final outcome review R1](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/final-review.md#must-fix-before-future-selection) independently confirms the defect and missing realistic regression. Execution adherence PASS/report APPROVE is not source readiness or seed qualification.
- Exact frozen source: [handoff.py](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/preparation-1/handoff.py), `validate_common:678–688`, especially683–686; callers `validate_inspection:725–732`, `evidence_chain:744–748`, normal acceptance945–948 and recovery955–972. SHA256 `f9c319839f8490674c8f0efc173fed999b12e4e22e5405d791cb6964a6e88be4`.
- [Guest guards](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/preparation-1/guest.py) require mounted data device1792/mount50 at363–371; source receipts use the held inode at778–782. Root/tool executable device2049 is a distinct identity. [Host fixtures](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/preparation-1/test_handoff.py):58–75,324–328,366 incorrectly use2049 for data; executable2049 at331–332 must not be globally replaced.
- Immutable [failed attempt](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/attempt-1/result.json) SHA256 `7f587d5715cc15b93bb427f79d3ca6e3d504283cf953365d9cef87dcc95afffc`: `BOUNDED_STOP`, stress `UNKNOWN`, accepted receiver custody unverified; incomplete and seed NOT_RUN. [Actual report](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/task-3-report.md) retains independently reviewed release/receiver-wait evidence without promoting accepted UNKNOWN.
- Related open [transport issue019](issue-20261025-025228-019-qualify-bulk-seed-transport-around-libssh-zero-win.md) remains unresolved. This host-device defect does not establish the historical libssh/Multipass causal sequence or qualify a new bulk path.

## Acceptance and execution boundary

- An offline realistic data1792/executable2049 chain must pass `validate_inspection` and `evidence_chain`, including terminal-only failure custody.
- An actually wrong source device2049 must fail even with internally consistent receipt hashes and links. Retain inode/spec/nonce/hash/writer/terminal corruption rejections, source mount50, and exact device checks; do not remove the check, accept either device, or broadly loosen validation.
- Reuse actual retained receipts read-only as regression input where useful. Do not rewrite consumed source/manifest/approval/attempt evidence or reclassify its UNKNOWN; correct and review a separately selected successor package.
- A new reviewed selection with fresh-resource/time bindings is required before any live attempt. No retry, renewed clock, occupied-path reuse, extra guest check, worker/Go/native startup or acquisition is authorized by filing or resolving this issue. Incomplete control and real5373-file seed admission remain separate required gates; no archival of019 follows from an offline fix.

---
<!-- id: issue-20261025-134521-520-bind-sftp-host-source-device-to-selected-data-file  file: issue-20261025-134521-520-bind-sftp-host-source-device-to-selected-data-file.md -->
