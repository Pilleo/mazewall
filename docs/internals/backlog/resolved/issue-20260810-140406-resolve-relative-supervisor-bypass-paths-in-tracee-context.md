---
title: "Resolve relative supervisor bypass paths in the tracee context"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ResolveAbsolutePathTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Resolve relative supervisor bypass paths in the tracee context

**Context:** `SupervisorSessionHandler` checked a raw relative `openat` or `openat2` path with
`BypassPaths.isBypassPath` before resolving it against the tracee's `dirfd` or current working
directory. `Path.toAbsolutePath()` consequently interpreted the path relative to the supervisor
daemon. A tracee could use a bypass-looking relative spelling with an unrelated directory file
descriptor and receive `SECCOMP_USER_NOTIF_FLAG_CONTINUE` for a target outside the bypass roots.

**Needed:** Resolve every relative file path through `/proc/<pid>/fd/<dirfd>` or
`/proc/<pid>/cwd` before performing bypass membership checks. Regression coverage must use a live
tracee with a directory descriptor whose base differs from the supervisor working directory.

**Resolution:** Removed raw-path bypass matching. The handler now tests only the canonical path
returned by tracee-aware resolution, and the regression test verifies that `build/secret` under an
unrelated tracee directory does not match the daemon's `build` bypass root.
