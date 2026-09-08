---
title: "FD token ownership: type-level Owned/Unowned split + audit ledger + literal-int sweep"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 7ff336eb-80d5-49db-b91e-930a6e68508d
paperclip_identifier: MAZ-673
---

# 🔴 [Severity: HIGH]: FD token ownership: type-level Owned/Unowned split + audit ledger + literal-int sweep

**Context:** FileDescriptorTest/FileDescriptorReproductionTest minted tokens around invented
integers (10, 90, ...) and closed them - real close(int) syscalls in the shared worker JVM.
The lazily opened /dev/urandom fd of NativePRNG was destroyed, so every later SecureRandom user
(JUnit @TempDir via Files.createTempDirectory) failed with EBADF; gradle worker pipes were also
hit, killing unrelated test batches. Failures moved nondeterministically between call sites.
Production minting is currently clean (all from owned SafeLocalFd results), but the API surface
(generic()/unsafe()) permits this class of bug and 61 literal-int minting sites remain across
enforcer/profiler/platform tests. Full incident write-up:
docs/internals/designs/core/fd-token-ownership.md

**Progress:**
- ✅ FdEpoch audit ledger implemented with ownership tracking
- ✅ `markOwned()` method to mark fds as owned (called by adopt() and replace())
- ✅ `isOwnedThroughEpoch()` to check if an fd was marked as owned
- ✅ `verifyKernelLiveness()` stub (needs fcntl implementation)
- ✅ `auditClose()` to warn when closing unowned fds (enabled via -Dmazewall.fd.audit=true)
- ✅ Updated adopt() and replace() to mark fds as owned
- ✅ Updated close() to call auditClose()
- ✅ Type-level split: Implemented FdOwnership enum (Owned/Unowned) with ownership property on FileDescriptor
- ✅ Updated close() to enforce ownership and throw IllegalStateException for Unowned descriptors
- ✅ Updated all factory methods: unsafe()/generic()/role-specific return Unowned, adopt()/replace() return Owned
- ✅ Kept SupervisorSessionHandler.kt internal visibility changes for testability
- ⏳ Sweep and classify literal-int minting sites: Started fixing SupervisorSessionHandlerTest, many more files remain
- ⏳ Adopt ForeignFdGuard across enforcer/profiler suites: Currently only in SeccompConnectionTest
- ⏳ Audit pid-handle discipline: Not yet started
- ⏳ Fix remaining test files to use replace() for invented integers

**Needed:**
1. Type-level split: generic()/unsafe() return an Unowned token without close();
   only open*/adopt/replace/claimDupIfNeeded yield Owned tokens with close rights.
2. FdEpoch audit ledger (mazewall.fd.audit=true): verify target liveness via
   fcntl(F_GETFD) before close; log closes of fds never opened through the epoch.
3. Sweep and classify all 61 literal-int minting sites in tests (equality-only
   assertions are safe; anything calling close() must use owned integers).
4. Adopt ForeignFdGuard (platform test sources) across enforcer/profiler suites.
5. Audit pid-handle discipline analogously (invented pids + signal-bearing syscalls).

## Resolution evidence (2026-09-07)

- `FileDescriptor` carries `FdOwnership` at the type level: raw and role-specific factories produce `Unowned`; only `adopt`, `replace`, and kernel-result adoption produce `Owned` close-capable tokens.
- `FdEpoch` records ownership and, in `mazewall.fd.audit=true`, now checks `fcntl(F_GETFD)` before close. A kernel-dead descriptor is logged and its second close is suppressed.
- `FileDescriptorTest` proves audit mode rejects an epoch-live token after its kernel descriptor has closed.
- The raw unowned literal-factory sweep now finds only two mock/equality uses, neither close-capable. Owned replacement fixtures are protected by `ForeignFdGuard` across every JUnit test class that mints them; the remaining Kotest design spec routes close operations through its injected transport.
- Verified: `./gradlew :platform:test --tests io.mazewall.core.FileDescriptorTest`, `./gradlew :platform:cleanTest :platform:unitCheck`, and focused guarded enforcer/profiler suites.
