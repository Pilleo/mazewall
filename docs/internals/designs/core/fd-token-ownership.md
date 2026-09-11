# File-Descriptor Token Ownership — Invariants & Incident Notes

> Status (2026-09-10): Native acquisition receipts and active descriptor identities are implemented. Read this before
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
   factories return `Unowned` tokens that cannot call `close()`. Successful native
   descriptor acquisitions attach a single-use receipt to their result.
   `getFdOrThrow(context, role)` consumes it. A fabricated numeric `Success`, or
   a copied/mapped numeric result, has no acquisition authority.
2. **Active identities**: a monotonic process-wide identity distinguishes every
   acquisition, including reuse of the same integer. Close atomically removes
   the active identity before calling the kernel, including on native error.
   Closed identities and historical ownership ledgers are not retained. Typed
   owned views share their lifecycle, and repeated/concurrent closes cannot
   dispatch another native close. Borrowed raw views remain the external owner's
   responsibility; this tracking cannot detect unreported external close/reuse.
3. **Test fixtures**: `TestFileDescriptors` and `testAcquisition()` live only in
   shared test sources. Invented numbers are permitted only against injected
   test engines. Tests using the real engine must acquire their own kernel
   resources and use `ForeignFdGuard`; never close an invented number.
4. **PID discipline**: signal-bearing paths create PID tokens only from pidfds
   returned by `pidfd_open`; never mint one around an invented process identifier.

### Migration

`FileDescriptor.adopt(Int, ...)` and `replace(Int, ...)` retain their JVM symbols,
but are deprecated and throw with an instruction to consume native acquisition
evidence. Keep the native result until `getFdOrThrow` consumes it; extracting an
integer first loses that authority. Use `FileDescriptor.withRole(owned, role)`
when changing the typed view of an already owned descriptor.
For `socketpair`, consume the result twice with `getFdOrThrow`: the receipts
follow the output array order. A third consumption fails, as does a second
consumption of a scalar acquisition result.

`SupervisorSocketUtils.connectDescriptorWithRetry` returns an owned socket.
`SupervisorSeccompNotifInstaller.installOwned` accepts a typed connector and
transfers that owned socket to its callback. Their legacy integer-returning and
integer-callback entrypoints are deprecated fail-fast shims. The receiver owns
callback cleanup even if the callback fails. No integer-to-ownership registry or
thread-local ownership recovery is provided.

### Native transfer checks

The real native boundary centrally checks managed segment/count pairs before
passing them to FFM: nonnegative counts within segment capacity, live and
accessible native memory, and writable output buffers. Array counts for poll
and iovec headers use division before multiplication to avoid overflow. Paths
must contain a NUL terminator within their segment. The native C layouts and
calling conventions are unchanged.

`NativeDescriptorInputStream.read(byte[], off, len)` checks the Java array range
before any native I/O, including zero-length reads. A success count outside the
requested range is rejected before copying. Raw integer addresses, pointers
embedded inside native structures, and remote iovec target addresses still
require trusted callers: an address by itself does not prove an allocation's
size or ownership, and segment checks cannot recover that information.
Raw syscall dispatch accepts managed buffers only at explicitly supported pointer
positions, with syscall-specific byte counts, element counts, or fixed layouts.
Unknown or misplaced managed buffers and mixed nonzero raw pointers are rejected.
The wholly raw integer-address path remains a trusted-caller escape hatch; a
zero-address, zero-size managed NULL is also accepted as scalar zero for legacy
callers. Managed ioctl buffers require a known payload size: FIONREAD/TIOCINQ
uses a four-byte C int, while unsupported zero-size request encodings fail before
native dispatch rather than inferring a kernel payload from the allocation size.

## Seccomp daemon cleanup outcomes

SCM_RIGHTS receipt atomically applies CLOEXEC with `MSG_CMSG_CLOEXEC`. Only one
complete descriptor in an untruncated message transfers to the caller. Rejected
messages release every complete SCM_RIGHTS descriptor delivered into the control
buffer; padding and non-rights ancillary data are never treated as owned integers.
Independent closes are attempted before cleanup failures propagate. UNIX server
address validation occurs before socket acquisition, and bind/listen failures
release the acquired socket while preserving the primary failure.

Daemon and session owners interpret `SeccompCleanupInterpreter.close(connection)` as
`Complete` or `Incomplete(failures, retryableOwnership)`. Independent descriptor
closes are all attempted. A failure before native dispatch stays on the original
connection for retry. A native close error is observable but terminal: Linux may
already have released the integer, so its ownership is retired even on error.
`RealSocketManager` reports these errors distinctly, preserving `FdEpoch` retirement
before native close; no generation is revived. Retrying the connection
does not close descriptors already released by an earlier attempt or by the reactor.
The compatibility `AutoCloseable.close()` on `SeccompConnection` remains best-effort;
owners that manage shutdown must consume the typed result.

The shared daemon retains failed connections and server descriptors, exposes
`cleanupOutcome`, and offers `retryCleanup()`. Its diagnostic outcome keeps earlier
failures visible after a successful retry, with an empty ownership list when no
pre-dispatch failure retains a descriptor. Native close errors likewise have empty
retryable ownership. Worker drain failure is recorded, and tracked client
descriptors are not closed while workers still run. Session buffer returns and arena
closure are independent cleanup attempts; their exceptions are aggregated. Cleanup
restores interruption and propagates cancellation after independent releases.

Owners use the recording overload of `SeccompCleanupInterpreter.close` and resource
cleanup callbacks to persist the complete outcome before cancellation propagates or
the interrupt flag is restored. Shutdown history therefore survives unobserved worker
futures and later successful retries. `RealProfilerTransport.close` delegates to
`RealSocketManager.close`, so the production profiler observes the same terminal
native-error semantics as the shared daemon.
