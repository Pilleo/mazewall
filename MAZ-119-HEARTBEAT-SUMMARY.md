# MAZ-119 Heartbeat Summary - 2026-08-30

## Issue
MAZ-119: FD token ownership: type-level Owned/Unowned split + audit ledger + literal-int sweep

## Severity
HIGH

## Progress This Heartbeat

### Files Modified
1. **enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt**
   - Fixed `closeLocalFd()`: Changed `FileDescriptor.generic(fd)` to `FileDescriptor.adopt(fd, FileDescriptorRole.Generic)`
   - Fixed fd cleanup in `closeTraceeFds()`: Changed `FileDescriptor.generic(dupFdSafe.fd)` to `dupFdSafe.handle` to use existing owned FD
   - Changed visibility of several methods from `private` to `internal` (required for test reflection)

2. **enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt**
   - Replaced ALL 48 occurrences of `FileDescriptor.unsafe<Role>(value)` with `FileDescriptor.replace<Role>(value)`
   - Added imports for `io.mazewall.core.Pid` and `io.mazewall.core.Tid`
   - Changed visibility of several handler methods from `private` to `internal` for test accessibility

3. **demos/cli-demo/src/main/kotlin/demo/ProfileAndEnforceDemo.kt**
   - Fixed io_uring ring FD: Changed `FileDescriptor.unsafe<Generic>(value.toInt())` to `FileDescriptor.adopt(value.toInt(), FileDescriptorRole.Generic)`
   - Fixed io_uring close: Changed `FileDescriptor.unsafe<Generic>(value.toInt())` to `FileDescriptor.adopt(value.toInt(), FileDescriptorRole.Generic)`

### Key Distinction: `adopt()` vs `replace()`
- **`adopt(fd, role)`**: Used in production code for FDs obtained from kernel (dup, accept, SCM_RIGHTS, syscall results). Marks the FD as owned in the epoch. Can be closed.
- **`replace<Role>(value)`**: Used in tests for invented integers (10, 11, -1, etc.). Creates a non-owned token that will NOT perform actual close() syscalls. Safe for test mocks.

### Verification
- ✅ Platform tests pass (all 122 tests)
- ✅ Enforcer main code compiles
- ✅ Enforcer test code compiles
- ✅ Demo code compiles

### Remaining Work

#### Literal-Int Sweep (Priority: HIGH)
The following test files still contain `FileDescriptor.unsafe` or `FileDescriptor.generic` calls that need classification:

1. `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ResolveAbsolutePathTest.kt` - 4 occurrences
2. `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SeccompSessionHandlerTest.kt` - 2 occurrences
3. `enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngineTest.kt` - 14 occurrences
4. `enforcer/src/test/kotlin/io/mazewall/landlock/LandlockApplyResultTest.kt` - 1 occurrence
5. `enforcer/src/test/kotlin/io/mazewall/landlock/LandlockCoverageTest.kt` - 11 occurrences
6. `enforcer/src/test/kotlin/io/mazewall/ffi/memory/SupervisorExecPathInspectionTest.kt` - 2 occurrences
7. `enforcer/src/test/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtilsTest.kt` - 3 occurrences
8. `enforcer/src/test/kotlin/io/mazewall/ffi/networking/SupervisorValidationChannelTest.kt` - 3 occurrences
9. `enforcer/src/test/kotlin/io/mazewall/LinuxNativeCoverageTest.kt` - 3 occurrences
10. `enforcer/src/test/kotlin/io/mazewall/NativeEngineTest.kt` - 1 occurrence
11. `enforcer/src/test/kotlin/io/mazewall/core/SocketIoTest.kt` - 4 occurrences
12. `enforcer/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngineTest.kt` - 2 occurrences
13. `src/sharedTest/kotlin/io/mazewall/MockSocketManager.kt` - 3 occurrences

**Total remaining: ~55 occurrences**

Each occurrence needs to be classified:
- If it's a **real FD from a syscall or kernel operation** → Use `adopt()`
- If it's an **invented integer for testing** → Use `replace()`
- If it's **equality-only assertion** (e.g., `assertEquals(expected, actual)`) → Safe as-is, no close() called

#### Type-Level Split (Priority: HIGH)
- [ ] Modify `generic()` and `unsafe()` to return `Unowned` state
- [ ] Modify `close()` to throw `IllegalStateException` for `Unowned` descriptors
- [ ] Ensure only `adopt()`, `replace()`, `claimDupIfNeeded()` yield `Owned` tokens

#### ForeignFdGuard Adoption (Priority: MEDIUM)
- [ ] Add ForeignFdGuard to enforcer test suites
- [ ] Add ForeignFdGuard to profiler test suites

#### Pid-Handle Audit (Priority: MEDIUM)
- [ ] Review all pid-related syscalls (kill, signal, etc.)
- [ ] Ensure invented pids are not used with signal-bearing syscalls

## Blockers
None. The existing API allows the fixes to be applied incrementally without breaking changes (thanks to the `replace()` function for test integers and `adopt()` for production FDs).

## Next Steps
1. Continue literal-int sweep across remaining test files (estimated 2-3 heartbeats)
2. Complete type-level split implementation (estimated 1 heartbeat)
3. Adopt ForeignFdGuard across test suites (estimated 1 heartbeat)
4. Audit pid-handle discipline (estimated 1 heartbeat)

## Git Changes
```
 M demos/cli-demo/src/main/kotlin/demo/ProfileAndEnforceDemo.kt
 M enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt
 M enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt
```

## Build Status
- ✅ Platform: BUILD SUCCESSFUL, all tests pass
- ✅ Enforcer: Compilation successful, test compilation successful
- ⚠️ Enforcer integration tests: Require container environment (expected)
