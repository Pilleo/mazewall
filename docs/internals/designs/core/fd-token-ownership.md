# File-Descriptor Token Ownership — Invariants & Incident Notes

> Status (2026-09-07): The ownership split and audit ledger are implemented. Read this before
> minting [FileDescriptor] tokens in tests or production.

## The incident (2026-08-24)

`FileDescriptorTest` / `FileDescriptorReproductionTest` minted tokens around
**invented integers** (`generic(10)`, `generic(90)`, …) and called `close()` on them.
Every such call is a real `close(int)` syscall in the shared test JVM:

- The lazily opened `/dev/urandom` fd of `NativePRNG` was destroyed → every later
  `SecureRandom` user failed with `IOException: Bad file descriptor`.
  First visible victim: JUnit `@TempDir` (`Files.createTempDirectory`).
- Gradle worker pipes were hit too → `Gradle Test Executor N finished with non-zero
  exit value 1`, killing whole batches of unrelated tests.
- Failures moved nondeterministically between call sites depending on which integer
  got shot — the classic signature of cross-test pollution via kernel handles.

## Invariants

1. **Ownership before minting.** Only pass integers this process obtained from its
   own opens (`open`/`openat`/`dup`/`accept`/SCM_RIGHTS). Never literals, never
   numbers guessed from /proc of other processes, never "it worked last run".
2. **Close only what you own.** `FileDescriptor.close()` is a real syscall. A token
   around a foreign integer is a loaded weapon aimed at whatever holds the slot:
   SecureRandom seeds, classloader jars, logging handlers, selector eventpipes,
   agent output files.
3. **Claiming is not neutral.** `generic(int)` registers the integer in `FdEpoch`;
   claiming a foreign number can falsely retire it for the *legitimate* owner later
   (spurious generation mismatch → EBADF-unless-live denials).
4. **Tests are first-class citizens of this rule.** A test that needs descriptors
   opens them (`openPath("/dev/null", OpenFlags.RDONLY)` with a confined arena) and
   uses the returned numbers. `ForeignFdGuard` enforces this: any descriptor present
   before a test and missing afterwards fails the test, named by number and target.

## Enforcement today

- `ForeignFdGuard` (platform test sources), registered on the FD-lifecycle test
  classes. Disable only for diagnostics: `-Dmazewall.fdguard=off`.
- KDoc DANGER notes on `FileDescriptor.generic()` and `unsafe()`.

## Implemented controls

1. **Type-level ownership split**: `generic()`/`unsafe()` and role-specific raw
   factories return `Unowned` tokens that cannot call `close()`. Kernel-result
   adoption (`adopt`, `replace`, and dup-result handling) yields `Owned` tokens.
2. **Audit ledger mode**: with `mazewall.fd.audit=true`, `FdEpoch` verifies
   `fcntl(fd, F_GETFD)` before close, logs untracked close attempts, and suppresses
   a close when the kernel has already invalidated the descriptor.
3. **Literal-factory sweep**: the remaining raw literal tokens are mock/equality
   uses and cannot close. JUnit tests that mint owned replacement fixtures use
   `ForeignFdGuard`, which fails a test that closes a descriptor present at start.
4. **PID discipline**: signal-bearing paths create PID tokens only from pidfds
   returned by `pidfd_open`; never mint one around an invented process identifier.
