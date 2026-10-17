# JVM worker completion profile investigation

## Scope

Continuation from integrated c1af6c26 investigates the remaining FutureTask/
VarHandle report without weakening kernel protection or changing runtime admission.
Work stays in the existing authorized worktree on `fix/jvm-liveness-fixture-profile`.

## Retained fatal evidence inspected

Exact retained files were located read-only in the separately owned checkout:

- `.worktrees/repository-cleanup/enforcer/hs_err_pid88950.log`: GraalVM25.0.4.1.1,
  pool-2-thread-1, FutureTask.set -> VarHandleGuards.guard_LI_V -> native-wrapper
  generation -> executable CodeHeap expansion65536bytes -> EPERM. The callable has
  already returned and is absent from this stack. Its source test/profile cannot
  be proven from that trace alone. The current dispatcher uses named
  mazewall-sandbox-* workers, so its cache test is not established as this caller.
- `hs_err_pid199455.log`: Ubuntu OpenJDK25.0.4.1, same generic worker name but
  applyBpfFilter offset385 -> ContainmentDesignSpec. This is the callback path
  already fixed by c1af6c26, not another FutureTask-set occurrence.

The retained command log says a JvmFloorWorkloadTest filter was selected for the
second failure, while its actual VM stack identifies ContainmentDesignSpec. These
are retained observations, not enough evidence to resolve the historical selection/
concurrency provenance. No unsupported source attribution is substituted.

## Concrete fixture mismatch

ContainmentDesignSpec promised a pre-warmed JVM task could complete while choosing
NATIVE_IMAGE, which intentionally denies executable mappings. Even completion of
an already warm arithmetic task can link a native MethodHandle/VarHandle wrapper
in FutureTask.set. Successful prewarming is not a runtime-safety contract.

The fixture now declares HOTSPOT_JIT explicitly and performs an actual executable
mmap through its captured NativeEngine, constructs a NativeMapping only for the
successful native return, closes it on the worker and checks the result after
Future completion. Arithmetic and installed-receipt assertions remain. Its new
probe deterministically fails under the old fixture profile
(`run-c38a9fe850f4`), independently of code-cache allocation timing.

Only the two dispatcher cache tests that execute actual JVM tasks use HOTSPOT_JIT;
metadata-only cache policy projections stay unchanged. Each test retires the
cached pools before restoring its fallback setting. No new fallback or skip is
introduced. This makes fixture intent match the already-defined substrate.

No production policy/default, BPF instruction, PROT_EXEC inspection, coordination
syscall, layout, runtime detection or admission behavior is changed. Strict
NATIVE_IMAGE mapping-denial assertions remain in their dedicated native protection
tests; permitted JVM executable mappings do not imply permitting execve/execveat.

## Verification checkpoint

Focused actual GraalVM execution passes the17-case design fixture, dispatcher cache
tests and cold-install JFR regression, plus ktlint and test Detekt
(`run-cd362d1e9570`). The wider original-worker investigation remains open because
the old FutureTask-set trace alone cannot identify its source callable.
The subsequent complete quality/native-protection checkpoint is recorded below.

## Final review and quality gate

The complete native-enabled build passes on installed GraalVM25.0.4.1.1 in9m50s
(`run-c396aeb3d7d5`). Final XML independently confirms design17, dispatcher cache5,
cold-bootstrap1, strict BpfHardening15, MmapProtection17 and public HotSpot7 tests,
all with0skips/failures/errors (`run-d62ec4f2095f`). Formatting, static analysis,
configured coverage and included module gates pass.

Scoped connected-code review checks only the two actual execution cache fixtures
changed profile; metadata-only cache definitions and all strict native protection
policies/assertions remain. Mapping ownership derives from the actual native
success, is closed before the result is published, and pool retirement precedes
restoring test-global fallback state. No production source, dependency or ABI is
changed; test naming no longer claims warmup makes strict HotSpot work safe.

The precise fixture issue is archived. Canonical counts remain137active
(136open/1deferred), with706resolved. The broader original FutureTask provenance
investigation stays open. Retained fatal reports and failed old-profile control
are not discarded or reclassified as successful runs.

Post-archive ADK checks pass (`run-751e02fc41bc`), final fixture formatting and
Gradle index validation pass (`run-57de383e025f`), and222local references plus
recursive issue totals pass (`run-e3e1d5a78f1c`). Whitespace checks pass. Only
whitespace/documentation changed after the complete executable quality gate.
