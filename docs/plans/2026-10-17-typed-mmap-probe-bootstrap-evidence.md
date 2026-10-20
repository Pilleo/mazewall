# Typed mmap probe bootstrap correction

## Failure exposed by complete commit verification

The strengthened commit hook is integrated as13bf92b0 and requires build without
excluding check. Its first actual GraalVM run correctly rejects a native failure
(`run-2f8bff6c9276`); unchanged full verification on installed OpenJDK then succeeds
in10m39s (`run-a0fca0ece8cd`), producing the hook commit. Logs are preserved under
`build/reports/precommit-full-build/{graal-native-failure,openjdk-success}.log`.
The failed GraalVM run is not treated as fixed by that runtime comparison.

`enforcer/hs_err_pid3623280.log` identifies the exact failing path:
MmapProtectionTest.pkey_mprotect -> anonymousMapping -> RealNativeFileSystem.mmap
-> SyscallInvoker.mmap offset120 -> cold MethodHandle invocation linkage ->
native executable CodeHeap expansion65536bytes denied EPERM. This precedes the
intended protection assertion; it is not a successful denial observation and is
distinct from the earlier string-concat and no-op callback paths.

## Root cause and minimal correction

The fixture created an unused raw libc mmap downcall handle in companion
initialization. It never invoked the typed NativeEngine mmap path that the actual
probe uses after containment. Creating a different raw handle does not prepare
that production call site's adaptive invocation shape.

Replace that dead raw-handle construction with one non-executable read/write
allocation through existing anonymousMapping before the filter is installed.
Existing withOwnedMapping derives ownership from the actual native result, runs
the warmup body and performs checked munmap, preserving original and cleanup
causes. No invented closeable/address, new FFM layout, carrier widening, errno
handling change or direct native call is introduced. NativeEngine still owns the
actual downcalls and immediate errno capture.

The exact typed invocation and checked cleanup now happen before PROT_EXEC denial;
the actual test then observes the same unchanged strict native mapping/protection
results. mprotect warmup and its error/raw-return controls remain. This prepares a
specific known fixture call, not arbitrary JNI/JVM work under NATIVE_IMAGE.

## Focused checks and commit boundary

Uncached actual GraalVM MmapProtectionTest and fresh-JVM BpfHardeningTest pass,
along with ktlint and integration Detekt (`run-319b3b20a152`). Existing actual
EPERM/return-value/cause/suppression and physical cleanup assertions are retained;
no policy, production source, fallback, skip or test assertion is weakened.

The source diff deletes unused raw handle construction and reuses the existing
owned mapping lifecycle. The complete commit hook is the final quality gate for
the committing revision; its full build must succeed before Git can create it.
The broader historical executable-mapping issue remains open for distinct paths.
