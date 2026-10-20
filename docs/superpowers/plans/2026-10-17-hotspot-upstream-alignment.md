---
document_type: execution_plan
base_revision: 8da8b4efcc0b72d9c4f06cf798e6daee0d9b1120
status: proposed_requires_operator_selection
---

# Upstream-Aligned HotSpot Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only after operator selection; reuse only an explicitly authorized workspace.

**Goal:** Reuse existing upstream generation checks, correct native-accounting acquisition planning and diagnose actual public-readout cost without upgrading production guarantees.

**Architecture:** Keep the independent workstreams in their owning plans. The revised generation plan characterizes the prepared upstream implementation and publishes a source-scoped audit; the accounting plan retains its reviewed native gate; the consumer plan adds test-only JFR collection to the existing public fixture.

**Tech Stack:** Existing Go1.26/xsys/Cilium/prepared modules, Kotlin/JDK25/JFR/JUnit, pinned native fixtures and current Go build overlays; no new dependencies.

**Spec:** `docs/plans/2026-10-17-hotspot-upstream-alignment-spec.md`, supplementing `docs/plans/2026-10-17-hotspot-remaining-work-spec.md`.

## Global Constraints

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Production remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry` until a separately approved capability gate passes.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Raw FFM remains behind NativeEngine/io.mazewall.ffi; confined arenas, immediate errno capture, acquired descriptor ownership and checked close.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Never install seccomp from a virtual thread or change JVM coordination syscall floors; do not touch GITHUB_TOKEN.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

---

## File structure and independent deliverables

|Plan|Files/responsibility|Approval/dependency|
|---|---|---|
|[A: revised generation audit](2026-10-17-hotspot-upstream-generation-audit.md)|Actual upstream regression overlay; one mirrored audit model; verified-manifest candidate sidecar; accurate lifetime decision|No new production capture field or runtime admission; source-scoped proof only|
|[B: corrected selected accounting](2026-10-17-hotspot-production-loss-binding.md)|Existing native producer/owner/model/pin work, with early metadata validation and acquired-map binding separated|Task1 already committed; Task2 native implementation requires operator approval of the contract|
|[C: consumer diagnostics](2026-10-17-hotspot-consumer-readout-diagnostics.md)|Existing public fixture's test-only JFR mode, paired actual runs and honest bottleneck decision|Existing pinned graph only; no reader/queue/deadline optimization by assumption|

Existing completed tasks and their evidence are recovered from
`.superpowers/sdd/<owning-plan-basename>/progress.md` in the authorized worktree.
Do not redispatch completed Plan01, Plan02 measurement/evidence, Plan03 or Plan04
Task1. Preserve all old reports/partial packets and source-receipt limitations.

## Execution preparation

- [ ] Check actual status/ancestry and select a scoped branch without changing the source checkout:

```bash
git status --short --branch
git merge-base --is-ancestor 8da8b4efcc0b72d9c4f06cf798e6daee0d9b1120 HEAD
git log -5 --oneline
```

The existing authorized worktree is `.worktrees/hotspot-review-remediation`.
Verify existing isolation with using-git-worktrees; do not create another worktree
or reset operator work. Source checkout Codanna edits remain operator-owned.

- [ ] Check the existing prepared graph and assets; preparation is an explicit prerequisite if absent:

```bash
(cd profiler/hotspot-agentless && go run ./cmd/mw-collector-tools patch-id)
(cd profiler/hotspot-agentless && go run ./cmd/mw-collector-tools verify --bundle build/assets)
```

Expected patch618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c.
If either graph/asset is absent, preserve any incomplete owned output and run the
existing operator-selected prepare/build path. Do not edit module cache/generated
sources or silently use an unverified graph. The new host overlay/audit does not
require a new production bundle; B's native implementation is separately gated.

## Execution order and review stops

1. Execute A Tasks1–3 as a coherent source-audit/reporting change; Task1 tests
   existing behavior, Task2/3 use genuine red/green for the new audit/report.
2. Execute C Tasks1–2 separately. It consumes the existing pinned public fixture,
   not A's static audit or B's unapproved new native map.
3. Obtain explicit operator approval for B's corrected native contract before
   source/pin changes. Its existing tests, reproducibility and actual public proof
   gates remain mandatory after approval.
4. Review the native lifetime decision before any JIT/GC/Loom admission. A compile
   cookie, clean source audit or diagnostic workload is not that approval.

## Commit and verification policy

Each child task has an exact scoped commit and focused checks. Actual commit hooks
already run `./gradlew build` plus configured portal gates; preserve their logs and
do not run a duplicate unchanged full build merely to satisfy a second checklist.
Native Go tests/overlays must be run explicitly because Gradle is not their proof.
No hooks are disabled, checks excluded, warnings relabeled as clean, or skipped
capability cases called evidence. Documentation-only revisions need local/Git
reference checks, backlog validation and whitespace checks.

## Planning self-review coverage

|Spec|Owning executable tasks|What cannot be inferred|
|---|---|---|
|A: existing compile-ID generation validation|A1 real upstream function+mutants; A2 typed source contract; A3 shared sidecar/decision|No live loaded-object or metadata-retention certification|
|B: post-acquisition binding|Revised B2 Step4 early validation/copy, post-assignment bind and checked cleanup|No native operator approval or executed new map proof|
|C: actual public consumer cost|C1 opt-in JFR fixture; C2 paired runs/JFR analysis/decision|No dense promotion, zero-cost conclusion or unmeasured optimization|
|D: pinned/version/provenance separation|A1 receipts and source hashes; A3 verified manifest; C2 classpath/graph receipts|No main-branch rebase or repair of old missing receipts|

All consumed new names are defined in the owning task. Plans use `Task N` headings
for the existing brief extractor, YAML metadata for backlog validation, and the
requested implementation header as their first Markdown body content. New files
under this directory are Git-ignored; save them locally and use exact scoped
staging when publishing the approved requirements, never blanket-force-add scratch.
