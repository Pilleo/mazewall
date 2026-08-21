---
title: "Restrict quoted paths to filesystem syscalls"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819470487
---

# 🟡 [Severity: MEDIUM]: Restrict quoted paths to filesystem syscalls

**Context:** For strace network records such as `sendto(3, "payload", ...)`, this unconditional extraction treats the quoted payload as a filesystem path. `BobCompiler.applySyscall()` then sends unrecognized non-filesystem paths through its default read-path branch, so the returned Bill of Behavior gains bogus `opens` entries and an explicitly compiled policy can grant Landlock access to a path derived from network data.

**Problem:**
- Network payload treated as filesystem path
- Bogus opens entries in Bill of Behavior
- Landlock access granted to network data

**Impact:**
- Policy grants Landlock access to non-filesystem paths
- Incorrect Bill of Behavior

**Needed:**
1. Only extract paths for path-bearing syscalls
2. Don't treat network data as filesystem paths

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819470487
