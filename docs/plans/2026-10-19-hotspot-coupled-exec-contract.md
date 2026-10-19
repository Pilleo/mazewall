# Coupled JVM exec qualification contract

Scope: complete the existing A4 target-exec/exit controls from
[the approved A3 specification](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/specs/2026-10-18-kubescape-agentless-hotspot-design.md#a3-native-qualification-exit),
after drain/custody commit1728e661. No runtime admission, SDK publication, new native
wire/layout, dependency, privilege change, worktree or external repository is added.

The existing shell-only exec control does not establish JVM/full-readout coupling.
Replace it with a sixth real workload case in the same owned capture path. Keep
the maintained400-call/four-worker/two-route baseline unchanged. A new standalone
`ObservedExecWorkload` wraps that fixture and uses a gated platform thread to
replace its own JVM image with `/bin/sleep 4` after marked work, while the collector
is still attached. The sleeping replacement preserves the PID long enough to
check the exec poison independently of process exit, and exits normally without
requiring a deliberately failed target cleanup.

## Fixture/native boundary

Use only the pinned JDK25 standard library: no JNI library/agent or additional VM
option. The wrapper's private `NativeEngine` encapsulates the one libc `execv`
downcall; high-level gates never handle raw segments. Initialize its linker before
readiness. Allocate argv strings, three ADDRESS entries and captured errno state
inside a confined arena on the invoking platform thread. Use C int=`JAVA_INT`,
null-terminated UTF8 strings and an explicit NULL argv terminator. Capture errno
using `Linker.Option.captureCallState("errno")` and read it immediately if execv
returns. Preserve errno/name and fixture errors; failure is not a fallback to the
shell control. No collector/native metadata reader is inserted in the application.

FFM contracts consulted: official JDK25
[captureCallState](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html#captureCallState(java.lang.String...))
and [segment allocation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/SegmentAllocator.html).
The fixture boundary is deliberately standalone; it cannot import the library's
internal FFI module into an otherwise plain pinned-container JVM launch.

## Required evidence and controls

- Compile/hash both maintained Java files and all resulting classes before use;
  bind the new file into matrix InputDigest. Ordinary/churn/unload launch the old
  main class; only target-exec launches the wrapper. Fixed warmups40, marked400,
  workers4, requested pacing20ms and all native/SDK budgets remain unchanged.
- Require verified collector readiness and all intended marked operations before
  triggering exec. Retain before/after proc records proving same PID, raw birth,
  cgroup and mount namespace, and the actual changed executable. A token file alone
  does not prove successful exec. Resolve `/bin/sleep` inside the independently
  associated container before triggering exec; its actual canonical path can be
  the pinned image's uutils path rather than `/usr/bin/sleep`. Require exact
  equality afterward, not a basename or a name derived from the changed process.
  Missing replacement/native errors reject.
- Child diagnostics preserve original capture error plus typed exec/exit causes.
  A qualification-tagged predicate may expose the private target-exit sentinel to
  this actual cross-package test caller; it is absent from shipped builds. No
  string matching is used to fabricate an expected native cause.
- An expected exec/exit rejection must not produce an accepted SDK Capture or a
  successful summary. The collector process must actually reap before releasing
  its target. Unknown/failed remote retirement still retains actual owners.
- For deliberate target SIGKILL, independently record Docker state/exit137 and
  the original typed target-loss error. Expected negative-control termination may
  pass its *control*, but every original error and cleanup receipt stays visible.
  Only expected exit137 causes may be classified that way: mixed EPERM/EACCES,
  timeout, unjoined, failed query/close and other exit codes remain failures.
- Behavioral host tests must cover fixture selection, compiler inputs, exact
  rejection classification and mixed/unknown failures before implementing the
  corresponding logic. Then execute one new receipted matrix on read-only assets,
  retain its first real verdict, and inspect native permission-child retirement.
- A qualified controlled observational report, if actually obtained, still needs
  separate operator admission review. Strict2, absent metadata/code/mount leases,
  incomplete global collection and false policy eligibility are unchanged.

Owning issue remains
[the observed drain/lifetime qualification](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/internals/backlog/resolved/issue-20261018-212457-115-observed-hotspot-capture-rejects-native-definition.md).
