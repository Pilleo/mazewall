---
title: "Keep native errno diagnostics free of cold dynamic JVM bootstraps"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":platform", ":enforcer"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/native/LinuxNative.kt"
  - "platform/src/test/kotlin/io/mazewall/SyscallResultTest.kt"
target_symbols: ["throwErrno", "LinuxNative.SyscallResult.Error"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Keep native errno diagnostics free of cold dynamic JVM bootstraps

**Context:**
The exact d3c21cef CI38822914139 kernel failure has a retained fatal report:
MmapProtectionTest's genuine pkey_mprotect(PROT_EXEC) denial calls getOrThrow;
Error.throwErrno's cold invokedynamic concat links a native wrapper and expands
HotSpot CodeHeap on the deliberately executable-mapping-restricted worker.
That incidental65536-byte executable mmap is correctly denied EPERM and the VM
aborts. Preserve the tested filter, actual denial/errno/raw result, exception
type and message; remove only dynamic bootstrap requirements in the shared error
renderer. Broader historical FutureTask/VarHandle mapping failures stay separate.

**Needed:**
1. Add a deterministic compiled-class regression excluding invokedynamic from the native error adapter, preserve all existing message/result contracts, and render through direct StringBuilder operations. Verify actual native protection cases without policy/VM-limit/test-assertion relaxation.

## Root-cause evidence

CI fatal report1451 names pool-11-thread-1, MmapProtectionTest.pkey_mprotect,
Error.throwErrno→resolve_invokedynamic→native wrapper→CodeHeap.expand_by and
executable mmap EPERM (`run-c8eeae4a78b4`, local private diagnostic receipt).
Compiled-class RED `run-f106f042948d` deterministically catches the bootstrap
instructions even when the VM code-cache boundary is not hit. Impact tool is
PARTIAL (`run-964e38d59b3c`); exact native result/mapping callers inspected.

## Resolution

The shared throwErrno/toString adapter now renders through direct StringBuilder
operations and one private append helper. Existing exact messages, exception
type, errno/raw values and success semantics remain unchanged. The compiled
Error class has no invokedynamic instructions. The guard uses the build JDK's
standard classfile API only in tests; published library/runtime dependencies and
the JDK22 floor are unchanged.

All native-result contract tests and actual unchanged MmapProtectionTest denial
cases pass (`run-27e5f96c269a`). Full included-module build and all three unchanged
mutation gates pass together (`run-348e89e8365a`,468174ms). The earlier interrupted
verification was checked for surviving wrapper/test workers before continuation;
it is not represented as a passing command. Connected review confirms no policy,
VM code-cache limit, assertion, native binding/layout or API/message relaxation.
This resolves the specifically observed adapter bootstrap; broader historic
FutureTask/VarHandle executable-mapping cases remain open in issue120935-494.
Exact-head remote CI remains required before merge.

---
<!-- id: issue-20261015-234007-259-keep-native-errno-diagnostics-free-of-cold-dynamic  file: issue-20261015-234007-259-keep-native-errno-diagnostics-free-of-cold-dynamic.md -->
