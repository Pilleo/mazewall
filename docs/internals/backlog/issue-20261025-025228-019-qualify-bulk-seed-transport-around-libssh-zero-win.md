---
title: "Qualify bulk seed transport around libssh zero-window false EOF"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-25-hotspot-bulk-transport-investigation/libssh-research/metadata/libssh-connector/body"
target_symbols:
  - "ssh_connector_fd_in_cb"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Qualify bulk seed transport around libssh zero-window false EOF

**Context:**
HotSpot VM bulk seed remains blocked. Official libssh 0.11.3 commit 301d0e16dfa8a5cac1cff956b6880ca90eb82864 permits a zero-window connector read of length zero, then treats zero as EOF; pinned Multipass 1.17.0 uses this connector. Source-level path and local model independently reviewed; actual installed-snap callback/window sequence remains unobserved, so historical transfer causality is UNKNOWN. Do not patch/upgrade the host, change guest permissions, or resend automatically.

**Needed:**
1. Qualify a bulk transport path that bypasses the affected stdin connector while preserving the existing bounded guest filesystem, original partials, exact file/hash admission and independent receiver termination; or obtain scoped runtime evidence for the zero-window callback before selecting a patch.

## Evidence and limits

- Official libssh0.11.3 commit `301d0e16dfa8a5cac1cff956b6880ca90eb82864`:
  [connector.c](https://raw.githubusercontent.com/libssh/libssh-mirror/301d0e16dfa8a5cac1cff956b6880ca90eb82864/src/connector.c), lines233–266 and513–562;
  [packet.c](https://raw.githubusercontent.com/libssh/libssh-mirror/301d0e16dfa8a5cac1cff956b6880ca90eb82864/src/packet.c), lines1520–1541.
- A socket-write-ready callback can enable the FD-input connector with zero remote window. Its requested read size becomes zero; `read(fd, buffer, 0)` can return0 with unread input and an open writer, then the connector attempts SSH EOF. No inspected guard excludes this path.
- Official Multipass1.17.0 commit `7022358648d38522c4c3351195ad141bfafe2a83`, [ssh_client.cpp](https://raw.githubusercontent.com/canonical/multipass/7022358648d38522c4c3351195ad141bfafe2a83/src/ssh/ssh_client.cpp), lines103–145, uses libssh connectors on Linux. The separate `read_console()` loop is Windows-only.
- Four source-anchored connector model cases, four outer-loop model assertions and six owned-boundary local checks passed. Independent review accepted conditional source reachability, not runtime causality. Local receipts are in `.superpowers/sdd/2026-10-25-hotspot-bulk-transport-investigation/`.
- The real guest read3679205bytes before a genuine EOF on blocking stdin; the host accepted3744741bytes before BrokenPipe. No None/timeout was recorded. These observations fit the mechanism but do not identify the actual SSH window/callback sequence or its initiating component. The65536-byte difference does not identify a particular queue.
- No installed tool/library patch, upgrade, guest permission change, additional live transfer or alternative transport was qualified. This is an external-tool qualification blocker, not a demonstrated mazewall runtime defect.

## Acceptance

- Use version-pinned source/runtime evidence to select one transport remedy or diagnostic; do not treat throttling, chunking or a larger buffer as an established fix.
- Qualify the selected path with backpressure and an incomplete-input control before another real seed transfer. Preserve exact hashes/modes, the existing12GiB guest bound, independent receiver wait/custody and all historical failures/UNKNOWNs.
- A successful small transfer or source model alone does not close this issue. Preserve fail-closed permission/space handling; no global host-security or credential changes are implied.

---
<!-- id: issue-20261025-025228-019-qualify-bulk-seed-transport-around-libssh-zero-win  file: issue-20261025-025228-019-qualify-bulk-seed-transport-around-libssh-zero-win.md -->

## SFTP qualification follow-up — still open

The selected SFTP batch stopped at stress host receipt validation; incomplete and real seed were NOT_RUN. [Source-device follow-up520](issue-20261025-134521-520-bind-sftp-host-source-device-to-selected-data-file.md) tracks the separately confirmed host literal2049 versus selected data filesystem1792 mismatch and strict offline regressions. [Final review](../../../.superpowers/sdd/2026-10-25-hotspot-sftp-seed-handoff/final-review.md) approves execution/report adherence and independently supports recorded exposure release/receiver wait, not accepted transport or seed qualification. Frozen accepted UNKNOWN and historical causal UNKNOWN remain unchanged. No retry/new clock is authorized; this issue is not archived or closed by that report or an eventual offline device fix.
