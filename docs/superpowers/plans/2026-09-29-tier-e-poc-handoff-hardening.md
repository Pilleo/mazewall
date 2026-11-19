---
title: "Harden opt-in Tier E evidence handoff, finalization reporting and operator helper"
document_type: execution_plan
status: "open"
base_revision: 6aa481897d6cbb7ccfee67b7e95a69fa188f1b15
---

# Tier E PoC Handoff Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the opt-in Tier E evidence handoff usable by a non-root reader, make finalization failures observable, and remove manual marker-offset/control framing from the operator's example.

**Architecture:** Three separately reviewable deliveries. A shared evidence-file access policy is used by the event and terminal report sinks; a daemon-owned failure outcome preserves finalization/cleanup evidence; a packaged operator-side helper reuses the existing registration parser, ELF resolver and target control client. No wire-protocol or kernel capture changes are required.

**Tech Stack:** Kotlin/JDK 25, POSIX file attributes, Unix-domain sockets, Gradle application distribution, existing rootful Tier E integration fixture.

**Spec:** `docs/internals/designs/profiler/tier-e-split-privilege-deployment.md` (trust boundary, target registration, terminal publication and all-target handoff).

**Recommended order:** Delivery 2 (lost terminal outcome), Delivery 1 (cross-UID handoff), Delivery 3 (optional operator convenience). The deliveries are independently testable and reviewable.

## Global constraints

- Keep `TIER_E_ALL_TARGET_CAPTURE` opt-in, `policy_eligibility=false`, exact target admission and existing control-protocol v1 unchanged.
- Default evidence files remain root-owned and owner-only. An opt-in group must never relax directory ownership or make an incomplete `.tmp` a completed artifact.
- A failed finalization must never be reported as successful capture, producer closure or clean resource retirement. Preserve the original failure, attempt independent cleanup, and leave an owner reachable for retry when cleanup fails.
- The operator supplies trusted host PID, library path, session tag and artifact directory; the target client must not gain the ability to authorize an arbitrary PID or change the collector's registered target.
- Keep dependencies unchanged and preserve unrelated worktree/backlog changes. Create or amend scoped backlog items before implementation; coordinate finalization with the existing open cleanup-ownership and shutdown-terminal items under `docs/internals/backlog/`.
- Execute inline unless the operator explicitly authorizes delegation.

---

### Delivery 1: Opt-in readable evidence files

**Files:** Create `profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEEvidenceFileAccess.kt`; modify `TierEEventJsonlFileSink.kt`, `TierETerminalReportFileSink.kt`, and the evidence-root wiring in `TierEKotlinDaemon.kt`; test in `TierEEventJsonlFileSinkTest.kt`, `TierETerminalReportFileSinkTest.kt` and the separate-daemon integration test; document in the split-privilege deployment design.

**Interface:** A validated `TIER_E_EVIDENCE_GID=<numeric host GID>` selects owner/group read (`0640`) for *both* the final `.events.jsonl` and `.tier-e.json`. Without it, both stay `0600`. This does not reuse `TIER_E_SOCKET_GID`, which governs a separate control surface. The operator must provide an evidence directory traversable by that group and consistent GID mapping across mounts; the daemon must not chmod or chown the directory.

- [ ] First add failing host tests: verify default owner-only mode, configured GID and `0640` on each final file, invalid/negative GID rejection at startup, and a simulated chgrp/chmod failure leaving **no final file**. Assert the existing byte/record exhaustion behavior still leaves only incomplete evidence.
- [ ] Add a small access-policy class: parse the optional GID once; apply group ID and then exact POSIX permissions to the *closed*, complete temporary file before atomic rename. Fail closed on any attribute or rename failure; keep the event sink's no-overwrite rule and terminal report publication semantics. Keep diagnostic `.tmp` files untrusted by readers.
- [ ] Run `./gradlew :profiler:test --tests '*TierEEventJsonlFileSinkTest' --tests '*TierETerminalReportFileSinkTest'` and `./gradlew :profiler:ktlintCheck`.
- [ ] In the rootful, separate-daemon fixture, verify an importer running with the selected group but a different, non-root UID can open both published files, while a nonmember cannot; verify mode/ownership after rename and that the default stays inaccessible. A rootless host test may cover parsing but cannot substitute for this access check.
- [ ] Update deployment guidance with the opt-in variable, directory traversal/GID-mapping requirement, and the rule that readers consume final names only. Commit this delivery separately.

### Delivery 2: Visible, incomplete finalization outcome

**Files:** Modify `profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt`; reuse `TierETerminalReport.kt` and `TierETerminalReportFileSink.kt`; add a focused daemon finalization fault test alongside existing control/registration tests. Touch `TierECollectorRuntime.kt`, `TierECollectorService.kt`, `TierECollectorLoop.kt` or `TierEProfilerSession.kt` only as required by the existing cleanup-ownership issue. Document failure semantics in the split-privilege design.

**Interface:** For a registered target, a thrown `runtime.finish(...)` yields a durable terminal report with `evidence_integrity=INCOMPLETE`, `terminal_reason=FINALIZATION_FAILED`, registration/session/PID/incarnation, zero *claimed* resolved syscalls, all proof dimensions false and `policy_eligibility=false`. Do **not** manufacture a profile or publish a completed `.events.jsonl`. `STATUS` must expose the failed reason; a DETACH/SHUTDOWN that triggered the failure must not reply with an unqualified successful finalization. For a legacy unregistered session, log and return an error without pretending a registered artifact exists.

- [ ] First add a deterministic injected-failure test that makes finalization throw (including permission failure), then checks that the terminal sink is called exactly once, no success result/event JSONL is published, `STATUS` shows the incomplete reason and the triggering control reply is an error. Test that repeated shutdown cannot upgrade or duplicate the failed terminal.
- [ ] Add a cleanup-failure test: fail the first close and succeed on retry; assert the original finalization failure remains primary, cleanup failure is retained, no second ATTACH starts while the owner is unretired, and the owner is not forgotten merely because `activeSession` was cleared. Align this with the open `preserve-tier-e-resource-ownership-until-cleanup` and `make-tier-e-shutdown-terminal` backlog items instead of introducing conflicting ownership fixes.
- [ ] Implement explicit finalization success/failure handling (no `runCatching { ... }.getOrNull()`), with one terminal-report attempt, structured stderr logging and an observable in-memory failure state. Attempt independent cleanup and preserve a failed owner for retry. A report-write failure must also remain visible through logs/control state; never return an `OK` implying completion.
- [ ] Run focused daemon, control-state, runtime/collector lifecycle and terminal-sink tests, then `./gradlew :profiler:test`. Run the rootful registered-target fixture with a controlled failure seam only if it exercises behavior that the host fault tests cannot establish; do not reuse a successful PoC receipt as failure proof. Commit independently after the full build gate.

### Delivery 3: Supported registration and ATTACH helper (lower priority)

**Files:** Add a small CLI entry point and strict registration writer in `profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/`; reuse `ElfSymbolOffset.kt`, `TierETargetRegistration.kt` and `TierETargetControlClient.kt`; add an installed start script in `profiler/build.gradle.kts`; add focused CLI tests and update the deployment design/example.

**Interface:** The packaged `tier-e-target-helper register` accepts operator-provided registration ID, host PID, session tag, collector-visible agent path, safe artifact filename and output path; it derives `/proc/<host-pid>/stat` start-time and `mazewall_stack_marker` ELF file offset, validates the resulting v1 registration with the existing reader, and writes a new operator-owned file without following or replacing a symlink/previous file. `tier-e-target-helper attach --registration <file> --socket <path>` uses `TierETargetControlClient.attach` with full-stream mode and exits unsuccessfully on an `ERR` reply. It does not add a new daemon command, dynamic target authorization or automatic permission changes.

- [ ] First test an ELF fixture and live PID to verify derived offset/start-time, exact v1 fields, duplicate/unsafe output rejection, exited-PID rejection, and a fake v1 Unix socket showing the client sends the expected modified-UTF ATTACH frame and surfaces `ERR` as a nonzero exit. Document that a container-local PID is not a substitute for a registered host PID.
- [ ] Implement the small CLI/writer using existing validated types; keep registration creation an operator action after the JVM PID exists. Package the helper as an additional start script without changing the existing `tier-e-daemon` launcher.
- [ ] Check `./gradlew :profiler:test`, build/install the profiler distribution and invoke the packaged command in a smoke test. Run the rootful daemon fixture with the helper-generated registration and helper ATTACH, verifying the existing JSONL/terminal reconciliation is unchanged.
- [ ] Document copyable launch/register/attach commands, including host-PID namespace, collector-visible library path and the separate agent-side definition path. Commit as its own delivery.

## Shared finish gate

- [ ] For each scoped delivery, check its backlog membership, `git diff --check`, focused tests and `./scripts/adkw check-backlog`; run `./gradlew build --max-workers=1` after host checks. Run privileged kernel verification only for a changed capture/attachment/lifecycle behavior or when a rootful permissions/control contract needs proof.
- [ ] Keep the original opt-in/default modes and prior source-pinned Tier E acceptance receipts separate from these new changes. Stage only the delivery's files; do not include unrelated operator-owned backlog entries or worktree changes.
