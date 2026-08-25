---
title: "StraceProfiler Multi-Path Extraction Logic Gap"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: StraceProfiler Multi-Path Extraction Logic Gap

**Context:**
Inside `StraceProfiler.kt`, the `extractQuotedPath` helper extracts only the first double-quoted path argument inside strace trace logs:
```kotlin
private fun extractQuotedPath(args: String): String? {
    val match = "\"(.*?)\"".toRegex().find(args)
    return match?.groupValues?.get(1)
}
```
This single-match regex assumes every filesystem-related system call contains exactly one file path. However, multi-path file system calls like `rename`, `renameat`, `renameat2`, `link`, `symlink`, `linkat`, and `symlinkat` pass *two* distinct path arguments (a source path and a destination/target path).

For example, a rename operation produces a log line like:
```
rename("old.txt", "new.txt") = 0
```
Because `extractQuotedPath` only retrieves the first match (`"old.txt"`), the profiler completely misses `"new.txt"`. Consequently, any generated `BillOfBehavior` (or resulting sandbox rule whitelists) will omit the destination path, resulting in incomplete and fragile whitelists that cause runtime containment failures or premature JVM crashes when the policy is later enforced in production.

**Needed:**
1. Refactor `extractQuotedPath` or `StraceProfiler`'s extraction logic to extract *all* double-quoted paths from the argument string instead of just the first match.
2. Update `parseLine` to support adding multiple extracted paths to `opens` or `fsWritePaths`.
3. Add a unit test to `StraceProfilerTest.kt` to verify that multi-path system calls like `rename` or `renameat` correctly register both the source and target paths in the resulting `BillOfBehavior`.
