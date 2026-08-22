---
title: "Extract only syscall pathname operands from strace"
severity: "MEDIUM"
status: "open"
priority: medium
component: "profiler"
dependencies: []
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
effort: "medium"
autonomy: "autonomous"
---

# Extract Only Syscall Pathname Operands from Strace

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

Fresh evidence after the multi-path parsing reply is that this regex now treats every quoted argument as a pathname rather than selecting syscall-specific operands. For example, `readlink("/tmp/link", "/secret", ...)` records the returned link text `/secret` as another read path, and `execve` records quoted argv entries as executable paths; `BobCompiler` can consequently widen the Bill of Behavior with paths that were never accessed.

## Impact

- Incorrect path extraction from strace logs
- Bill of Behavior widened with non-accessed paths
- Incomplete or incorrect policy generation

## Solution

Parse the defined pathname operands for each syscall instead of collecting every quoted string. Maintain syscall-specific operand mappings.

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt`
