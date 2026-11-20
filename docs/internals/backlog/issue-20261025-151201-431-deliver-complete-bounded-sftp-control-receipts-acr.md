---
title: "Deliver complete bounded SFTP control receipts across cases"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff-2/preparation-1/handoff.py"
  - ".superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff-2/preparation-1/guest.py"
  - ".superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff-2/preparation-1/test_handoff.py"
target_symbols:
  - "_custody_inspect"
  - "inspect"
  - "validate_inspection"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Deliver complete bounded SFTP control receipts across cases

**Context:**
Successor final review establishes cumulative all-case detailed inspection growth; two incomplete inspections timed out after identical 36864-byte prefixes. Actual runtime delivery cause remains UNPROVEN; frozen incomplete UNKNOWN and seed NOT_RUN are unchanged. Related transport019 remains open.

**Needed:**
1. First add an offline realistic complete two-case response and fragmented control-output regression beyond36864 bytes, with exact byte/hash completeness, timeout/truncation rejection and owned termination proof; review case-scoped detail plus a compact complete global inventory without weakening any identity or accounting gate.

## Evidence and scope

- [Independent successor final review](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff-2/final-review.md#source-finding-inspection-grows-across-cases-truncation-cause-remains-unproved) establishes cumulative all-case detail: guest `inspect:735–755`/dispatch1961–1962 and host adapter511–548 enumerate every existing case; freshness validation773–776 deliberately checks preceding-case inventory. Complete output grows2196→20824 bytes after stress; second-case detail repeats stress receipts/journal.
- Frozen [normal inspection](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff-2/attempt-1/incomplete/15-inspect.json) and [prescribed recovery inspection](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff-2/attempt-1/incomplete/16-inspect.json) each retain36864 stdout+248 stderr bytes, zero omitted output bytes, `TimeoutError` and local client wait-15. Their identical stdout SHA256 is `189dbd968832481ceb6e59f1301509e02ad7113b235773ce499c806b30e30a7f`; cut occurs inside the preceding stress journal's final `frameSpecSHA256` string. At least438 further journal bytes would follow, before tools/usage. No absent tail is reconstructed.
- Source drain multiplexes both pipes through EOF/exit and allows128KiB; guest canonical encoding checks128KiB before writing and loops over short writes. Parsing follows successful process completion. No36KiB cap, proven host drain/parser defect, or proven libssh cause follows. Retained bytes do not locate the actual stall/drop or prove every remaining byte was emitted; remote inspection-process/SSH-server terminal state remains UNKNOWN.
- [Separate complete-subobject phase proof](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff-2/final-review.md#separate-offline-proof-from-the-truncated-inspections) establishes incomplete negative-control/seal/owned receiver wait1 independently, not complete final inspection or frozen acceptance. Stress passed; immutable incomplete `UNKNOWN`/false/custody unverified and seed `NOT_RUN` remain unchanged. Related [transport019](issue-20261025-025228-019-qualify-bulk-seed-transport-around-libssh-zero-win.md) remains open; historical runtime causality is UNKNOWN.

## Needed acceptance and proposal

1. In a separately selected offline successor, exercise the realistic complete two-case inspection shape including preceding stress detail, and fragmented control responses beyond36864 bytes on both pipes. Assert exact bytes/length/hash, complete framing/EOF plus acquired local process wait, bounded timeout/truncation rejection and owned reaping; do not close missing JSON or relabel salvaged phase proof as frozen acceptance. Reuse retained records read-only; current single-case/upload drain tests alone are insufficient.
2. Review a case-scoped detailed receipt+journal response **with compact complete global case inventory** for freshness/occupancy, retaining full global usage/accounting, old-root identity/marker/usage, source mount50/device1792 versus executable2049, spec/nonce/job/actor/writer/inode/hash/phase-link/terminal gates and finite caps. Align guest output, adapter, validator and affected tests; adapter-only filtering after guest enumeration does not remove guest accumulation. This removes established redundant growth; it is a reviewable proposal, **not an established runtime-stall fix**.
3. Any later selected delivery qualification must bind emitted and delivered response completeness (exact length/digest) and terminal outcomes independently, then pass both complete controls before exact5373-file/1610506979-byte seed admission and receiver wait0. Design that proof offline first; this issue grants no live diagnostic or new attempt. Simply increasing deadlines/caps or guessing a libssh/host patch is not acceptance.

## Immutable boundary

Target paths identify frozen evidence, not files authorized for in-place repair. Preserve all source/manifests/approvals/captured specs,33+56 attempt files, original partials and UNKNOWNs byte-identical. No retry/new clock/occupied-path reuse/guest probe/cleanup/worker/Go/native/application/collector/image/import/API/public acquisition is authorized here. Retain original128MiB host aggregate/predecessor84201472 floor,4GiB guest old128MiB/new3968MiB partitions and12GiB backing, public174MiB charged/82MiB remaining; no refund. Issue520's separately confirmed correction does not qualify transport, archive019 or grant a seed/worker handoff.

---
<!-- id: issue-20261025-151201-431-deliver-complete-bounded-sftp-control-receipts-acr  file: issue-20261025-151201-431-deliver-complete-bounded-sftp-control-receipts-acr.md -->
