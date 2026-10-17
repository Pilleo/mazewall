---
title: "Record qualification source receipts and linked-worktree Go VCS limitations"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/admissionprobe/digest_linux.go"
  - "experiments/hotspot-agentless/internal/admissionprobe/digest_linux_test.go"
  - "experiments/hotspot-agentless/cmd/mw-admission-probe/main.go"
  - "experiments/hotspot-agentless/Makefile"
target_symbols:
  - "DigestFile"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Record qualification source receipts and linked-worktree Go VCS limitations

**Context:**
Task2 measured binary identities are verified but historical source-byte linkage is inferred/unverified; Go1.26.8 ignores the linked-worktree .git file and stamps ancestor checkout3099e2b0.

**Needed:**
1. Capture contemporaneous source/build/binary receipts for future qualification without rewriting historical evidence or changing upstream Go.

## Existing evidence and precise scope

The [committed qualification summary](../../plans/2026-10-17-hotspot-admission-next-evidence.md)
and local `.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/provenance-resolution.md`
record actual successful build commands, original measured binary hashes, current
source-to-commit SHA/cmp checks, installed Go1.26.8 source excerpts and a later
dry-run strace. Go discovery requires a .git directory, ignores this worktree's
regular .git pointer, and stamps ancestor3099e2b0 rather than worktree1d34405b.
Embedded paths/GOMOD corroborate location, not historical source bytes.
The original source-provenance.sha256 omitted the three new Go source files.
Binary identity is verified; exact historical source-byte linkage remains
INFERRED/UNVERIFIED. Later source hashes or rebuilds cannot repair that old receipt.
Known answers/full gate remain valid within these limits; native activation stays
not_authorized. This is experimental tooling evidence debt, not a production
vulnerability, and does not request an upstream Go fix.

## Acceptance criteria

- For future qualification, capture before-build source-input hashes (including
  tests/benchmark, module/sum and prepared replacement inputs if used), worktree
  HEAD/status, actual cwd/command/toolchain/options and environment relevant to
  source selection; verify input hashes unchanged after the build.
- Bind that build receipt to output binary hashes/build metadata and the actual
  measurement invocation/input graph before measuring. Keep raw commands/logs
  and distinguish requested-but-not-executed builds from successful builds.
- Document linked-worktree automatic VCS stamping limits without trusting a stamp
  as a source receipt. Canonical profiler/hotspot-agentless/Makefile already uses
  -trimpath -buildvcs=false; suppressing VCS metadata is distinct from recording
  source provenance. Review experimental build policy explicitly rather than
  changing canonical provenance semantics or upstream Go in this issue.
- Keep historical evidence/addendum intact and carry the old linkage limitation
  into activation review. Do not relabel a new receipt as proof for old binaries.
- Verify future receipts against a real qualification build in the authorized
  environment; no library/native provider or public API change is part of this issue.

---
<!-- id: issue-20261017-173125-908-record-qualification-source-receipts-and-linked-wo  file: issue-20261017-173125-908-record-qualification-source-receipts-and-linked-wo.md -->
