# Current-thread shadow-stack verification and locking

## Supported contract

`lockIntelCet()` requests Linux x86_64 **SHSTK enabled and locked on the installing
platform thread**. It does not establish IBT, WRSS hardening, or JVM/process-wide
CET coverage. Compatibility still depends on the runtime, loader, and native code.

MazeWall verifies an already-enabled shadow stack and locks its configuration.
It does not enable SHSTK through an ordinary late FFM call. Linux's shadow-stack
selftest explains that returning through a pre-enable function frame lacks the
corresponding shadow-stack return address; CPU/kernel availability alone does not
make this transition safe. The runtime/loader must establish compatible enablement
before library entry.

## Linux UAPI and evidence

| Operation | Value | Meaning |
|---|---|---|
| `ARCH_SHSTK_ENABLE` | `0x5001` | Enable a feature; not used by the library's locking path |
| `ARCH_SHSTK_DISABLE` | `0x5002` | Disable a feature |
| `ARCH_SHSTK_LOCK` | `0x5003` | Lock features at their current enabled/disabled state |
| `ARCH_SHSTK_UNLOCK` | `0x5004` | Ptrace-mediated unlock; never a status query |
| `ARCH_SHSTK_STATUS` | `0x5005` | Read enabled feature bits, not locked feature bits |

Confirmation requires an enabled SHSTK bit, native LOCK success **exactly zero**,
and successful enabled-status verification after locking. `EPERM`/`EACCES` are
failures, not evidence of an earlier lock. Unexpected nonzero success results are
rejected without treating an unwritten status buffer as evidence.

The readonly public status query reports enabled bits or zero for an inactive or
unsupported interface. It throws for permission/protocol failures. Its return
value alone never proves locking. Linux exposes distinct enabled and locked
fields in `/proc/thread-self/status` for independent native acceptance observations.

## Required execution, scope, and ownership

Receipts expose `CetOutcome.NOT_REQUESTED`, `UNCONFIRMED`, or
`VERIFIED_CURRENT_THREAD`. A requested but unconfirmed CET result cannot satisfy
`requireInstalled()`. Manual fallback can report a failed/bypassed installation;
wrapped tasks still reject before target entry and failed installation scopes remain
quarantined.

CET controls are per-thread. Process-wide CET requests are rejected before native
installation: Seccomp TSYNC does not apply CET to existing sibling threads, and a
dedicated JVM already has runtime threads. Each prepared/replacement worker must
establish its own evidence. Kernel feature inheritance is not a substitute for a
verified worker binding.

The registry retains proof for the exact platform thread and selected native backend.
Repeat installation reuses that successful proof rather than issuing another lock
and interpreting a permission error as idempotence. A different thread/backend must
establish fresh proof; quarantine takes precedence over cached evidence.

`InstallationProgress.cetLocked` records a newly successful native lock even when
later verification, diagnostics, Landlock, or Seccomp fail. A reused valid proof has
no new lock delta. Receipt closure only releases its supervisor-registration lease;
it neither removes CET/kernel restrictions nor owns another thread's proof.

This proof assumes the trusted runtime has not been externally modified by ptrace.
Linux documents ptrace-mediated unlocking and feature reset on exec. Receipt data
is diagnostic evidence, not tamper-resistant attestation or authority to move an
invocation to another thread.

## Verification and acceptance boundaries

Host tests independently expect UAPI status value `0x5005`; native mocks do not
derive their expected operation from the implementation constant. The fault matrix
covers disabled runtime, permission failures, malformed native returns, retained
proof, missing proof, partial lock progress, scope rejection, and quarantine.

Native tests use disposable child JVMs. Readonly status/proc observations and
inactive-runtime rejection can run without enabling SHSTK. Positive locking
acceptance requires a runtime/loader that already enables SHSTK, successful lock,
and matching per-thread enabled/locked proc fields. An explicit runtime prerequisite
skip does not establish positive CET protection. No general HotSpot/native-image or
process-wide CET claim follows from passing mocked tests.

## Primary references

- [Linux shadow-stack documentation](https://docs.kernel.org/arch/x86/shstk.html), §§15.3–15.6.
- [Linux v6.6 x86 UAPI constants](https://github.com/torvalds/linux/blob/v6.6/arch/x86/include/uapi/asm/prctl.h).
- [Linux v6.6 SHSTK implementation](https://github.com/torvalds/linux/blob/v6.6/arch/x86/kernel/shstk.c), `shstk_prctl`.
- [Linux v6.6 shadow-stack selftest](https://github.com/torvalds/linux/blob/v6.6/tools/testing/selftests/x86/test_shadow_stack.c), inline enablement rationale.

[Withdrawn historical research](research/intel-cet-legacy-notes.md) is retained for
provenance and is not an implementation recipe.
