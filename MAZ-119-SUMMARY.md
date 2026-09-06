# MAZ-119 Implementation Summary: FD Token Ownership

## Issue
MAZ-119: FD token ownership: type-level Owned/Unowned split + audit ledger + literal-int sweep

**Context**: FileDescriptorTest/FileDescriptorReproductionTest minted tokens around invented integers (10, 90, ...) and closed them - real close(int) syscalls in the shared worker JVM. The lazily opened /dev/urandom fd of NativePRNG was destroyed, causing EBADF errors in unrelated code (SecureRandom, gradle pipes). ~61 literal-int minting sites remain across enforcer/profiler/platform tests.

## Implementation Status

### ✅ COMPLETED - Core Functionality

#### 1. Type-Level Ownership Split
**File**: `platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt`

- Added `FdOwnership` sealed interface:
  ```kotlin
  public sealed interface FdOwnership {
      public data object Owned : FdOwnership
      public data object Unowned : FdOwnership
  }
  ```
- Added `ownership` property to `FileDescriptor`
- Updated all factories:
  - `unsafe()`, `generic()`, role-specific factories (`unixSocket()`, `ruleset()`, etc.) → return **Unowned** descriptors
  - `adopt()`, `replace()`, `claimDupIfNeeded()` → return **Owned** descriptors

#### 2. FdEpoch Audit Ledger
**File**: `platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt` (FdEpoch object)

- Added `owned` flag to `Slot` data class
- Added `ownedThroughEpoch` set to track FDs marked as owned
- Implemented methods:
  - `markOwned(fd: Int)` - marks FD as owned (called by adopt() and replace())
  - `isOwnedThroughEpoch(fd: Int): Boolean` - checks if FD was marked as owned
  - `verifyKernelLiveness(fd: Int): Boolean` - stub (circular dependency issue)
  - `auditClose(fd: Int, generation: Long): Boolean` - logs warnings when closing unowned FDs

#### 3. Ownership Enforcement in close()
**File**: `platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt` (line 634-649)

```kotlin
public fun <R : FileDescriptorRole, S : FdState.Open> FileDescriptor<R, S>.close():
    FileDescriptor<R, FdState.Closed> {
    if (value >= 0 && !isClosedType()) {
        // Enforce ownership at runtime
        if (ownership == FdOwnership.Unowned) {
            throw IllegalStateException(
                "Cannot close unowned FileDescriptor(fd=$value, role=$role). " +
                "Only descriptors created via adopt(), replace(), or claimDupIfNeeded() are owned."
            )
        }
        FdEpoch.auditClose(value, generation)
        LinuxNative.fileSystem.close(this)
        arena?.close()
    }
    return FileDescriptor.closedView(this)
}
```

#### 4. Production Code Fixes

All production code now uses `adopt()` for real FDs returned by syscalls:

- **Landlock.kt** (3 locations):
  - `mapCreateRuleset()`: `adopt()` for landlock_create_ruleset results
  - `addRuleFollowSymlinks()`: `adopt()` for openPath results
  - `addRule()`: `adopt()` for openPath results

- **SupervisorSeccompNotifInstaller.kt** (2 locations):
  - Exception handler: `adopt()` for socket FDs
  - Listener setup: `adopt()` for seccomp notif FDs

- **SupervisorInstaller.kt** (1 location):
  - JVMValidationListener creation: `adopt()` for socket FDs

- **SupervisorSessionHandler.kt** (3 locations):
  - All use `adopt()` for pidfd FDs from SafeLocalFd

#### 5. Function Signature Fixes

- **replace()**: Updated to accept `role: R` parameter (was missing, causing compilation error)
  ```kotlin
  public fun <R : FileDescriptorRole> replace(
      value: Int,
      role: R,
      arena: NativeArena? = null,
  ): FileDescriptor<R, FdState.Open>
  ```

#### 6. Test Fixes

- **FileDescriptorTest.kt**: All tests using real FDs now use `adopt()`
- **FileDescriptorReproductionTest.kt**: All tests now use `adopt()` for real FDs
- **NativeEngineTest.kt**: Uses `replace()` for invented integers in tests
- **ProfilerTraceListenerTest.kt**: Uses `replace()` for invented integers (300-304)

### ⚠️ REMAINING WORK

#### High Priority - Test Mocks
1. **SupervisorSessionHandlerTest.kt** - Multiple uses of `unsafe(10)`, `unsafe(11)` with invented integers
   - Lines 90, 91, 178, 179, 248, 249, 368, 369, 516, 517, 601, 602, 671, 672, 747, 748, 823, 824, 901, 902
   - These are passed to SupervisorSessionHandler constructor
   - Need to use `replace()` instead of `unsafe()`

2. **MockSocketManager.kt** - Uses `unsafe(10)`, `unsafe(11)`, `unsafe(12)`
   - These are returned from createUnixServer(), accept(), connect()
   - Need to use `replace()` or ensure tests use SocketManager.close()

3. **SeccompSessionHandlerTest.kt** - Uses `unsafe(10)`, `unsafe(11)`

4. **SupervisorDaemonEngineTest.kt** - Uses `unsafe(5)`, `unsafe(11)`, `unsafe(12)`, `unsafe(20)`, `unsafe(21)`

5. **Landlock test files** - Multiple uses of `unsafe(42)` in LandlockCoverageTest, LandlockApplyResultTest

#### Medium Priority
6. **Implement kernel liveness check** - `verifyKernelLiveness()` currently stubbed due to circular dependency with LinuxNative
7. **Adopt ForeignFdGuard** - Currently only in SeccompConnectionTest, needs to be across enforcer/profiler
8. **Audit pid-handle discipline** - Check for invented pids in signal-bearing syscalls

#### Low Priority
9. **Sweep remaining literal-int sites** - Classify all ~50 remaining sites in test files

### Test Status

| Module | Compilation | Tests | Notes |
|--------|-------------|-------|-------|
| platform | ✅ | ✅ PASS | All tests passing |
| profiler | ✅ | ✅ PASS | All tests passing |
| enforcer | ✅ | ⚠️ Partial | Compiles, SupervisorSessionHandlerTest fails (uses unsafe()) |
| portal | ❓ | ❌ FAIL | Integration tests fail (environment issues?) |

### Verification Commands

```bash
# Platform - All tests pass
./gradlew :platform:test

# Profiler - All tests pass  
./gradlew :profiler:test

# Enforcer - Compiles, some test failures
./gradlew :enforcer:compileKotlin
./gradlew :enforcer:compileTestKotlin
```

## Security Impact

### Before
- `unsafe()` and `generic()` created descriptors that could be closed
- Closing invented integers (10, 11, 90, etc.) destroyed real kernel resources
- No compile-time or runtime protection against this bug class

### After
- `unsafe()` and `generic()` create **Unowned** descriptors that **cannot** be closed
- Only `adopt()`, `replace()`, `claimDupIfNeeded()` create **Owned** descriptors that can be closed
- Runtime enforcement: calling `.close()` on Unowned descriptor throws `IllegalStateException`
- Audit ledger: when enabled (-Dmazewall.fd.audit=true), warns about closing unowned FDs

## Files Modified

### Core Implementation
- `platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt`
- `platform/src/main/kotlin/io/mazewall/core/SandboxedPath.kt`

### Production Code Fixes
- `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
- `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
- `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
- `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller.kt`

### Test Fixes
- `platform/src/test/kotlin/io/mazewall/core/FileDescriptorTest.kt`
- `platform/src/test/kotlin/io/mazewall/core/FileDescriptorReproductionTest.kt`
- `enforcer/src/test/kotlin/io/mazewall/NativeEngineTest.kt`
- `profiler/src/test/kotlin/io/mazewall/profiler/internal/ProfilerTraceListenerTest.kt`

### Documentation
- `docs/internals/backlog/code_health/issue-20260824-203500-fd-token-ownership-type-level-owned-unowned-split-audit-ledg.md`
- `MAZ-119-PROGRESS.md` (this file)

## Next Steps

1. **Immediate**: Fix SupervisorSessionHandlerTest to use `replace()` for invented integers
2. **This week**: Fix remaining test mocks (MockSocketManager, SeccompSessionHandlerTest, etc.)
3. **Next week**: Implement kernel liveness check, adopt ForeignFdGuard, audit pid-handle

## References

- **Design Document**: `docs/internals/designs/core/fd-token-ownership.md`
- **Backlog Issue**: `docs/internals/backlog/code_health/issue-20260824-203500-fd-token-ownership-type-level-owned-unowned-split-audit-ledg.md`
- **Issue ID**: MAZ-119
- **Paperclip Issue ID**: 7ff336eb-80d5-49db-b91e-930a6e68508d
