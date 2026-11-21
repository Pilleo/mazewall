---
title: "Validate managed prctl pointer arguments before native dispatch"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "core"
target_modules: [":platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
target_symbols:
  - "prctl"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate managed prctl pointer arguments before native dispatch

**Context:**
Repository-wide review, 2026-11-04: [PLAT-05](../../reviews/2026-11-04-repository-review-findings.md#plat-05). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · native memory safety · confirmed boundary defect with JDK-only marshalling probe · high confidence · platform native-process layer; no malformed production GetName call established.**

- **Evidence:** `platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt:613–621` passes all `PrctlCommand` arguments as longs with no `NativeTransfer` validation. `platform/src/main/kotlin/io/mazewall/platform/syscall/PrctlCommand.kt:51–67` accepts arbitrary managed name/buffer segments; `platform/src/main/kotlin/io/mazewall/platform/syscall/NativeArg.kt:38–43` calls only `.address()`. `platform/src/main/kotlin/io/mazewall/ffi/memory/ManagedSegment.kt:78–105` delegates that to the JDK address accessor. `platform/src/main/kotlin/io/mazewall/ffi/internal/SyscallInvoker.kt:226–234` then uses long arguments, so FFM receives no managed pointer scope to check.
- **Trigger:** `GetName(MemoryArg(oneByteSlice))`, a read-only 16-byte slice, a closed segment, or a confined segment used from another thread. Linux [kernel/sys.c:2524–2527](https://github.com/torvalds/linux/blob/v6.12/kernel/sys.c#L2524-L2527) writes `sizeof(comm)` (16 bytes) regardless of the Java slice extent. A one-byte logical slice backed by a larger mapped allocation therefore allows a write beyond its declared bounds; freed memory may also remain mapped, so EFAULT is not a safety mechanism.
- **Check run:** `01-platform-prctl-address.java/.log` proves on the current JDK that `.address()` accepts a one-byte read-only slice, cross-thread extraction, and a closed arena. It deliberately invokes **no prctl/native downcall**. The memory-corruption consequence is based on connected source/UAPI, not a destructive experiment.
- **Smallest correction:** command-specific managed validation (GetName: 16 writable live accessible native bytes; SetName: bounded readable name contract; validate other managed pointer variants explicitly). Preserve segment lifetime through native dispatch, using ADDRESS-typed managed bindings or an equivalent owned scope. Keep trusted raw-address operations distinguishable from managed ones.
- **Regression:** validator/downcall-spy rejects short, read-only, closed and wrong-thread managed buffers before native invocation. A guarded larger allocation can verify no adjacent-byte writes in an isolated negative test; a valid 16-byte GetName and ordinary SetName are positive controls. Existing `PrctlCommandTest.kt:34–58` only checks option/argument values, including NULL.
- **Tracking:** **expanded RH-01 hole**, resolved `issue-20260910-225751-359-validate-native-transfers-and-descriptor-acquisiti.md`; `fd-token-ownership.md:82–104` promises managed checks but only exempts explicit raw/nested pointers. No existing exact prctl-buffer issue found.
- **Dependency:** fix the native-process boundary once before adding dependent managed command callers; does not require changing the BPF prctl policy.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution Evidence (2026-11-21)
- Added exhaustive `NativeTransfer` validation in `RealNativeProcess.prctl` (`platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt`): writable `TASK_COMM_LEN = 16L` native segment check for `GetName`, bounded readable NUL-terminated path contract via `NativeTransfer.path` for `SetName`, readable `Layouts.SOCK_FPROG.byteSize()` check for `SetSeccomp(SECCOMP_MODE_FILTER)`, and `NativeTransfer.bytes` check for `SetMm`.
- Added unit tests in `RealNativeHelperDowncallTest.kt` and verified via `:platform:test`, `:lockdown:integrationTestFreshJvm`, and the full build gate.
- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).

---
<!-- id: issue-20261103-235245-104-validate-managed-prctl-pointer-arguments-before-na  file: issue-20261103-235245-104-validate-managed-prctl-pointer-arguments-before-na.md -->
