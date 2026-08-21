---
title: "Sealed Class Exhaustiveness for Path Resolution"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/BypassPaths.kt"
effort: "medium"
autonomy: "autonomous"
---

# ✅ [RESOLVED]: Sealed Class Exhaustiveness for Path Resolution

**Status:** RESOLVED (August 2026)
**Fix:** `BypassPaths.PathResolution` (`Resolved` / `Missing` / `Unsafe`). `isBypassPath` returns false on `Unsafe` (symlink loop, EACCES, SecurityException). `toRealPathWithFallback` still throws `FileSystemLoopException` for callers that must fail loudly.

# 🟡 [Severity: MEDIUM]: Sealed Class Exhaustiveness for Path Resolution

**Context:**
`BypassPaths.kt` contains logic for resolving "safe" filesystem paths to bypass seccomp filtering (like `/sys`, `/proc`, `.gradle`, or the `java.class.path`). Currently, path resolution aggressively uses `try/catch (e: Exception)` to swallow filesystem resolution failures and logs a warning. This broad exception catching masks underlying root causes and prevents the compiler from ensuring all filesystem edge cases (like symlink loops or permission denied errors) are securely handled.

**Needed:**
1. Extract filesystem resolution logic into a `PathResolver` utility.
2. Return a sealed `ResolutionStatus` (e.g., `Resolved(Path)`, `SymlinkLoop`, `PermissionDenied`, `NotFound`) instead of throwing/catching exceptions.
3. Use exhaustive `when` statements to strictly define how `BypassPaths` behaves for each distinct failure case, ensuring no hidden exceptions can bypass or subvert the sandbox boundary logic.
