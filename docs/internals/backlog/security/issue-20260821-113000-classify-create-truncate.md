---
title: "Classify create and truncate calls as filesystem mutations"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819982854
---

# 🔴 [Severity: HIGH]: Classify create and truncate calls as filesystem mutations

**Context:** When a resolved `CREAT` or `TRUNCATE` observation reaches this fallback, neither name is present in `isFileSystemMutation()`, so its path is placed in `opens` rather than `fsWritePaths`. Coverage can still be complete, but `toPolicy()` grants only Landlock read access while allowing the syscall, causing the generated policy to deny the file creation or truncation that the profile actually observed.

**Problem:**
- CREAT/TRUNCATE not in isFileSystemMutation
- Paths go to opens instead of fsWritePaths
- Policy grants read-only access
- Actual mutations are denied

**Impact:**
- Security: policy denies observed mutations
- Functionality: profiled behavior not enforced

**Needed:**
1. Add CREAT and TRUNCATE to isFileSystemMutation()
2. Or explicitly classify them as write operations
3. Ensure observed mutations are enforced

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819982854
