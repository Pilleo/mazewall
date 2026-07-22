---
title: "Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: true
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: LOW]: Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths

**Context:**
**Hypothesis:** If `socketPath` exceeds 108 bytes, `setupSockAddrUn` will throw an `IndexOutOfBoundsException` or cause memory corruption when copying path bytes into the `sockaddr_un` FFM struct.

In `SupervisorSocketUtils.setupSockAddrUn`, the length of the string is not bounds-checked before copying into the 108-byte `sun_path` struct layout using `MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)`. If the OS temporary directory path (`System.getProperty("java.io.tmpdir")`) is heavily nested, `Files.createTempDirectory` in `SupervisorDaemonManager` could produce a `socketPath` exceeding 108 bytes. This will cause `MemorySegment.copy` to crash the initialization of the supervisor.


**Needed:**
1. Add an explicit bounds check `require(pathBytes.size < 108) { "Socket path too long" }` in `setupSockAddrUn` and consider using the abstract namespace (`\0` prefix) or `openat`-relative binding if paths get too long.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
