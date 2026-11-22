---
title: "Preserve descriptor close errors and pre-dispatch cleanup ownership"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "core"
target_modules: [":platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/fd/FileDescriptor.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/fd/NativeCloseFailure.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
target_symbols:
  - "closeResource"
  - "PreDispatchCloseFailure"
open_questions: false
---

**Resolved — 2026-11-20:**

Native close(2) results now surface instead of disappearing: `NativeFileSystem.close`
Error results stay observable as `NativeCloseFailure` and retire the token (EINTR/EIO
results are never retried). `RealNativeEngine` claims once in `SyscallInvoker.close`
(prepare, claim, dispatch); a throw before the claim stays retryable and is classified
by the original generation's ownership, after the claim it is terminal. Failures are
classified by an exhaustive `CloseOutcome` (`Completed`/`StillOwned`/`Retired`); the
arena closes on terminal paths with a failure-preserving fold that cannot replace the
close failure. `NativeCloseFailure` moved to `io.mazewall.platform.fd` (curated into
the `:lockdown` world). Verified: claim-at-dispatch regressions in
`DescriptorIdentitySafetyTest`, `ConfinedHelperChannelTest`/`ConfinedHelperRelayTest`
alignment, `ConfinedHelperRuntimeDrainTest` import alignment, `ThreadPoolWhitelistTest`
baseline run (1 test, 0 failures), `:platform:test` green, `:platform:ktlintCheck` and
`:platform:detektMain` green, `:detektWarningRatchet` 0 added in every module touched.
The full host `./gradlew build -PmazewallKernelLane=BASELINE --continue` passed
after correcting the `BpfHardeningTest` fixture to send a valid name buffer and
fixing the whitelist and portal formatting. Production argument validation is unchanged.
The confined lane passed on the final code (verified receipt
`build/reports/confined-helper-1795217005949542797-1979154`, exit 0).
These checks do not establish published-artifact qualification.

# 🟡 [Severity: MEDIUM]: Preserve descriptor close errors and pre-dispatch cleanup ownership

**Context:**
Repository-wide review, 2026-11-04: [PLAT-04](../../reviews/2026-11-04-repository-review-findings.md#plat-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · cleanup/failure reporting · confirmed source defect · high confidence · all users of descriptor `close`/`use`/AutoCloseable adapters.**

- **Evidence:** `platform/src/main/kotlin/io/mazewall/platform/fd/FileDescriptor.kt:238–246` ignores `NativeFileSystem.close`'s `SyscallResult` and unconditionally marks the token closed in `finally`. `493–521` routes `close` and `use` through it. The real boundary retires the generation before dispatch (`platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt:327–329`). In contrast, `platform/src/main/kotlin/io/mazewall/platform/socket/SocketManager.kt:79–83` observes native Error and throws `NativeCloseFailure`. `docs/internals/designs/core/fd-token-ownership.md:116–126` promises observable terminal native errors and retryable pre-dispatch ownership.
- **Trigger/effect A:** close returns `Error(EIO/EPERM/...)`; `.close()` returns a closed view as if successful, and `.use()` cannot attach that error to an existing failure. A file-close writeback error or permission refusal is lost. `platform/src/main/kotlin/io/mazewall/ffi/networking/NativeDescriptorInputStream.kt:133–142` invokes this adapter for descriptor-owning streams.
- **Trigger/effect B:** a captured native adapter throws before claiming/dispatching close. `finally` still retires the only tracked owner and closes its arena, so the kernel resource cannot be retried through that token. `DescriptorIdentitySafetyTest.kt:60–110` injects just such a no-native-call failure but expects retirement; it does not prove that a real native close occurred.
- **Smallest correction:** consume and propagate the native result while retaining terminal retirement after an actual dispatch. Distinguish a pre-dispatch failure from an attempted native close and retain the former's owner. Release independent resources with the existing failure-preserving cleanup fold. Never retry an attempted Linux close merely because its result was EINTR/EIO.
- **Regression:** explicit native Error stays observable and dispatches once; block failure retains close Error as suppressed evidence; pre-dispatch throw keeps ownership for one later successful cleanup; distinct typed views cannot dispatch twice. Also verify arena failure cannot replace the close failure.
- **Tracking:** **expanded/incompletely represented RH-04 contract**, resolved `issue-20260910-225751-359-validate-native-transfers-and-descriptor-acquisiti.md`; cleanup taxonomy is related to resolved `issue-20260910-225754-299-record-explicit-lifecycle-and-cleanup-failure-evid.md`. Update the misleading test/manifest claim rather than treating the historical GREEN as coverage of the native-result branch.
- **Dependency:** foundational for truthful cleanup in stream/helper/daemon consumers; preserve `RealSocketManager`'s already-correct distinction.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235244-769-preserve-descriptor-close-errors-and-pre-dispatch-  file: issue-20261103-235244-769-preserve-descriptor-close-errors-and-pre-dispatch-.md -->
