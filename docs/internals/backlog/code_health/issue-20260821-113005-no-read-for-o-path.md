---
title: "Do not grant file reads for O_PATH observations"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912180
---

# 🟡 [Severity: MEDIUM]: Do not grant file reads for O_PATH observations

**Context:** When an observed `open` or `openat` uses `O_PATH`, `isOpenWrite()` returns false and this classifies the pathname as a read, even though `O_PATH` obtains only a metadata/path handle and does not require `LANDLOCK_ACCESS_FS_READ_FILE`. `toPolicy()` then adds an `allowFsRead` rule and unblocks open calls, allowing the sandboxed workload to reopen and read files that were only observed with O_PATH.

**Problem:**
- O_PATH classified as read
- But O_PATH doesn't require read access
- toPolicy() adds allowFsRead rule
- Workload can reopen and read files

**Impact:**
- More permissions granted than observed
- Security: O_PATH shouldn't grant read

**Needed:**
1. Don't classify O_PATH as read
2. O_PATH should not require allowFsRead
3. Only grant permissions matching observed access

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912180
