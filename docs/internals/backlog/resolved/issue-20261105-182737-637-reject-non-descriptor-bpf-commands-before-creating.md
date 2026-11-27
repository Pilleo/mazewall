---
title: "Reject non-descriptor BPF commands before creating owned descriptor receipts"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "platform"
target_modules: [":platform", ":profiler"]
target_files:
   - "platform/src/main/kotlin/io/mazewall/platform/native/NativeBpf.kt"
   - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
   - "platform/src/main/kotlin/io/mazewall/platform/fd/NativeDescriptorReceipt.kt"
   - "platform/src/test/kotlin/io/mazewall/platform/native/NativeBpfTest.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfMapAllocator.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadLayoutTest.kt"
   - "docs/internals/designs/core/fd-token-ownership.md"
target_symbols:
   - "openDescriptor"
   - "BpfDescriptorCommand"
   - "isBpfFdReturningCommand"
   - "recordDescriptorAcquisition"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject non-descriptor BPF commands before creating owned descriptor receipts

## Resolution — 2026-11-26

`NativeBpf.openDescriptor` requires a `BpfDescriptorCommand`. The enum is
also the source for raw syscall classification. The native adapter issues
the acquisition receipt and the typed opener consumes it once under the
BPF descriptor role. The Profiler map allocator uses the typed command.
[Descriptor ownership contract](../../designs/core/fd-token-ownership.md#native-transfer-checks)

The bare-success regression failed before the fix. On JDK 25, 59 focused
tests passed, covering command classification, single-use receipt identity,
missing receipts, native errno, and Profiler rollback. The full Platform suite
reported 551 passing tests. The strict offline full host build and confined
Docker lane passed in 17m21s and 3m03s respectively.

## Original finding

**Context:**
Recursive state-design review A-02: openDescriptor accepts any BPF command and records acquisition for arbitrary successful numeric status. MAP_LOOKUP_ELEM command 1 can return status 0 and manufacture close authority for descriptor 0; the only current production caller correctly uses MAP_CREATE. Source-confirmed internal misuse risk, not runtime-reproduced or a demonstrated valid-caller failure.

**Needed:**
1. Reject non-FD commands before dispatch using the existing authoritative command classifier; mint acquisition evidence once at the native adapter, with host spy and consume-once controls.

## Evidence and smallest correction

[Recursive review A-02](../../reviews/2026-11-05-state-complexity-review.md):
`NativeBpf.kt:24–32` records acquisition for every successful command;
`:42–57` already distinguishes descriptor-producing commands from command 1.
`RealNativeEngine.kt:132–144` classifies actual descriptor acquisition, so even
valid MAP_CREATE currently records again in the helper. Duplicate issuance alone
is not evidence of double-close or a failing valid caller.

A successful MAP_LOOKUP_ELEM status of zero must not create owned-close authority
for descriptor zero or replace an existing tracked generation. The only current
production caller, `TierEBpfMapAllocator.kt:34–44`, correctly uses MAP_CREATE.
This is an internal construction-contract risk, not a demonstrated sandbox escape.

Use the existing pure command classifier as the pre-dispatch gate. A narrow
internal validated command type is optional if it removes correlation across
entrypoints; no public command framework is needed. Dispatch, errno capture,
receipt creation and close remain explicit native-boundary effects. Ensure the
composed raw/BPF adapter path mints one receipt, then consumes it once.

## Acceptance and verification

- Host-only spy: command 1 has a fake success zero available only if dispatched;
  require rejection, zero dispatch, and no ownership/generation change to an
  injected test descriptor numbered zero. Never close real stdin or send invented
  descriptor integers to the real engine.
- MAP_CREATE and native-error controls retain the Bpf role, original errno and
  exactly one consumable acquisition receipt; repeated consumption fails and
  native close is attempted at most once.
- Exercise authoritative raw classification plus adapter composition to detect
  duplicate receipt issuance. Preserve raw status/count-returning commands as
  valid non-owning command operations.
- Run `NativeBpfTest` and `:platform:test`, then the required `./gradlew build`
  implementation gate. Actual BPF availability/permission qualification remains
  separate; this review ran no native operation or regression test.

## Existing ownership reconciliation

Active and resolved searches found no exact command-to-ownership owner. The
[resolved native-transfer correction](issue-20260910-225751-359-validate-native-transfers-and-descriptor-acquisiti.md)
remains resolved. The [descriptor-close issue](issue-20261103-235244-769-preserve-descriptor-close-errors-and-pre-dispatch-.md)
owns release of already-acquired resources, not acquisition from a status result.
No dependency on unrelated Tier E work or public API change is required.

---
<!-- id: issue-20261105-182737-637-reject-non-descriptor-bpf-commands-before-creating  file: issue-20261105-182737-637-reject-non-descriptor-bpf-commands-before-creating.md -->
