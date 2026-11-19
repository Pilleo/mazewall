---
title: "Reject unknown io_uring operations as complete read-only evidence"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/UringOp.kt"
target_symbols:
  - "FsEffect"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject unknown io_uring operations as complete read-only evidence

**Context:**
Repository-wide review, 2026-11-04: [EVID-07](../reviews/2026-11-04-repository-review-findings.md#evid-07). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/boundary:** reproduced classification/completeness defect; strategy-neutral compiler and recorded-eBPF diagnostics. **Tracking:** **EXPANDED** beyond resolved `docs/internals/backlog/resolved/issue-20260821-113005-reject-unenforceable-iouring-opcodes.md`; CONNECT/SEND/RECV/ACCEPT rejection exists, but unsupported/other opcodes still fall through.

- `UringOp.kt:49–68` produces `Unknown`; `FsEffect.ofUring():95–105` turns it into `Read`. `BobCompiler.kt:39–45` adds its paths to `opens`. `ProfilingCoverage.kt:277–302,340–361` rejects only `FsEffect.Unenforceable`, not unknown opcodes. `BillOfBehavior.kt:311` uses the same insufficient check.
- Trigger: `IORING_OP_FALLOCATE` with an observed path and clean drain/loss facts. The repro produces read-only behavior, `complete=true`, and accepted conversion. FALLOCATE is a real mutation-capable operation; `IORING_OP_FTRUNCATE` and non-covered network opcodes such as SOCKET/BIND/LISTEN are further concrete tokens the classifier does not recognize. Local Linux UAPI `/usr/include/linux/io_uring.h:271,295–309` and the [`io_uring_enter(2)` manual](https://man7.org/linux/man-pages/man2/io_uring_enter.2.html) distinguish these operations.
- Effect: misleading complete evidence and wrong/inexpressible policy capabilities at the shared model boundary. **Bounded reachability:** public recorded-log sessions independently force `drainComplete=false` (`MazewallProfiler.kt:98–106,139–169`), and generic live eBPF is unimplemented. Thus this is not a demonstrated current public recorded-log policy bypass.
- Correction: unsupported/unknown opcodes must be incomplete/unenforceable by default. Add exact supported operations only when their required operand/access evidence and enforcement consequence are representable; do not map every new mutation to an indiscriminate write grant.
- Regression: FALLOCATE/FTRUNCATE, unsupported network/ioctl-like tokens and truly unknown future tokens; preserve recognized writes and unknown-mode open refusals. **Prerequisite to any expansion of live/complete non-HotSpot eBPF policy support.**

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235400-980-reject-unknown-io-uring-operations-as-complete-rea  file: issue-20261103-235400-980-reject-unknown-io-uring-operations-as-complete-rea.md -->
