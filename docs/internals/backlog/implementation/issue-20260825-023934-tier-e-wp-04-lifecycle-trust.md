---
title: "Tier E WP-04: Session lifecycle & trust protocol"
severity: "ENHANCEMENT"
status: "resolved"
priority: high
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
target_files:
  - "ebpf-prototype/daemon/"
  - "ebpf-prototype/proto/"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023933-tier-e-wp-03-marker-uprobe-poc.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-04 — Session Lifecycle & Trust Protocol

**Context:** Turns the WP-03 primitive into a supervised service with a strict session contract.
Privilege split: the daemon loads BPF, creates maps, attaches programs; clients stay
unprivileged. Invariants 5–7 live here.

Design reference: [tier-e-design.md §4.4–§4.5, §5](../../designs/profiler/tier-e-design.md).

### Implementation note (2026-08-25 pivot)

Operator decision: the durable control plane is **Kotlin**, not C. The half-written C
daemon (`wp04_daemon.c`) is retained ONLY as a disposable protocol oracle for cross-
checking the `:tier-e-proto` Kotlin daemon; both implement the identical wire contract.
The C daemon is deleted before WP-05 begins so Gate G2 certifies the real
implementation. USDT attach in Kotlin ships uprobe-first per §4.1.1 sign-off.

### Resolution (2026-08-25) — RESOLVED

* Durable implementation is **Kotlin**: `:tier-e-proto` `TierEDaemon` (opt-in Gradle module)
  with per-connection epoch FSM, SO_PEERCRED uid-0 gate, ERR BUSY single-session rule,
  marker hygiene (realpath + inode-in-target-maps + NT_GNU_BUILD_ID) failing loud AND
  terminally, graceful SHUTDOWN via accept-wakeup self-connect, no pins anywhere.
* The C oracle validated the identical wire contract first (15/14-pass runs), then was
  **deleted** per pivot decision; the suite is Kotlin-only and requires PARITY-free green
  twice consecutively (achieved: 15/0 and 17/0).
* Kernel discoveries journaled: ringbuf writable-mapping restriction (see
  [testing/issue-20260825-191000-ringbuf-rw-data-mapping-eperm.md](../testing/issue-20260825-191000-ringbuf-rw-data-mapping-eperm.md))
  and rootless-docker-socket env leak (testing/issue-20260825-090500).

**Needed:**

1. Control socket `/run/mazewall/context.sock`:
   * filesystem ownership + restrictive permissions (root-owned, mode 0660 or tighter);
   * `SO_PEERCRED` validated immediately after accept;
   * duplicate session for the same target ⇒ reject new one (never silently share maps).
2. Session state machine (reuse the `UnixListenDaemonMachine` /
   `SeccompDaemonEngine/Machine/Handler` pattern from `:platform` — replicate inside the
   prototype until Gradle wiring):
   ```text
   RUNNING ──(EOF / error / shutdown)──► DEAD (terminal)
   ```
   * DEAD ⇒ no more trustworthy observations; no transition back to RUNNING.
   * Reconnect = NEW epoch = fresh map set + fresh attachments. Never splice pre/post-crash
     events into one trace.
3. **One map set per epoch; never recycled** (invariant 5). Task storage is scoped per-map
   instance, so a fresh map is empty for every task — stale entries from a dead epoch are
   unreachable by construction. Do not add epoch tags; per-map scoping already provides this.
4. **No bpffs pinning in v1** (invariant 6). Daemon owns all FDs; death detaches everything and
   restores the probed instructions (markers become inert plain calls).
5. Attach hygiene: resolve marker offset from the exact file mapped by the target — verify via
   `/proc/<pid>/maps` inode match + `NT_GNU_BUILD_ID`. Mismatch ⇒ loud failure, never silent
   misfire.
6. Graceful detach protocol: drain pending events, then detach, then close.

### Tests

```text
daemon kill -9 mid-run: breakpoints restored, target keeps running, markers inert
client reconnect after crash: new epoch, no stale-context events ever
wrong .so copy on disk: attach fails loudly with build-id diagnostic
second client while session active: rejected
socket perms violated: connection refused
```
