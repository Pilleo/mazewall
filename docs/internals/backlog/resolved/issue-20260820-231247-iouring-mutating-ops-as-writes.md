---
title: "Classify mutating io_uring operations as writes"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "profiler"
dependencies: []
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ebpf/*"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfileObservation.kt"
effort: "medium"
autonomy: "autonomous"
---

# Classify Mutating io_uring Operations as Writes

**Source:** Codex PR review comment 3797199301  
**Priority:** P2  
**Status:** Backlogged  
**Created:** 2026-08-20

## Problem

Every eBPF `IoUring` observation places its paths in `opens`, regardless of opcode. For recorded events such as `IORING_OP_WRITE`, `IORING_OP_UNLINKAT`, or a write-mode `IORING_OP_OPENAT`, the resulting Bill of Behavior therefore grants only `allowFsRead`; a policy accepted from otherwise complete coverage will deny the observed mutation when enforced.

## Impact

Mutating io_uring operations (writes, unlinks, etc.) are incorrectly classified as read operations, leading to:
- Policies that allow read operations but deny write operations
- Actual write operations being denied at enforcement time
- Incomplete policy coverage that doesn't match actual application behavior

## Solution

Route known mutating opcodes to `fsWritePaths` and retain enough flag information to classify open operations. Specifically:
- Identify mutating io_uring opcodes (WRITE, UNLINKAT, RENAME, etc.)
- Place paths from these operations in `fsWritePaths` instead of `opens`
- Preserve flag information to properly classify OPENAT operations based on their mode

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/ebpf/*` - eBPF event handling
- `profiler/src/main/kotlin/io/mazewall/profiler/ProfileObservation.kt` - IoUring observation structure
- Bill of Behavior compilation logic

## Notes

This is a coverage accuracy issue. The profiler needs to properly distinguish between read and write operations when processing io_uring events to ensure the generated policy matches the actual application behavior.
