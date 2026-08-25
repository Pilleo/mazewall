---
title: "Fix StraceProfiler path extraction to support multi-path system calls like rename and symlink"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: MEDIUM]: Fix StraceProfiler path extraction to support multi-path system calls like rename and symlink

**Context:**
The `StraceProfiler` (Tier P Profiler) traces system calls of a target workload class by running it in a child JVM process under Linux `strace`. It parses the resulting strace log file line-by-line to extract the filesystem path accesses.

**The Bug:**
Inside `parseLine()`, if a line matches a filesystem system call (e.g. `isFsSyscall(syscallName)` returns true), the path is extracted using `extractQuotedPath()`:
```kotlin
    private fun extractQuotedPath(args: String): String? {
        val match = "\"(.*?)\"".toRegex().find(args)
        return match?.groupValues?.get(1)
    }
```
The regex match `.find(args)` only extracts the *first* double-quoted argument in the system call argument list. This is correct for single-path system calls like `openat(AT_FDCWD, "file.txt", O_RDONLY)`.

However, for multi-path system calls (such as `rename`, `renameat`, `renameat2`, `link`, `linkat`, `symlink`, `symlinkat`), there are multiple paths in the argument list:
- `rename("old_name.txt", "new_name.txt")`
- `symlink("target.txt", "link_name.txt")`

Because `extractQuotedPath()` only matches the first double-quoted string, it completely misses the second path (`new_name.txt` or `link_name.txt`). As a result, the target of a rename/symlink/link operation is never parsed, leading to an incomplete `BillOfBehavior` profile, which will subsequently trigger unexpected `EACCES` or Landlock denials when the application runs under the compiled policy.

**Needed:**
1. Update `extractQuotedPath()` or `parseLine()` to extract *all* quoted path arguments for multi-path system calls.
2. Ensure that both the source and target paths for multi-path operations are added to the appropriate `opens` or `fsWritePaths` sets depending on the operation's nature.
3. Write a unit/integration test covering a `TraceableWorkload` that performs renames and symlinks to verify that both paths are correctly detected and compiled into the `BillOfBehavior`.
