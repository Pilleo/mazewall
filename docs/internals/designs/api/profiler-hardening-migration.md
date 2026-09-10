# Profiler evidence and Tier E migration

Profiler evidence is now owned at every public collection boundary. Callers must not depend on
constructor inputs being returned by identity. `ProfileObservation`, `CollectorDrain`,
`StackDefinition`, `SyscallAttribution`, `ProfilingCoverage`, and `TierEProfile` retain structural
equality and copy-style APIs, but their list/set projections are immutable snapshots.

Open-family strace observations expose `openFlagsEvidence`. `Complete(value)` means the exact flags
operand was decoded. `Incomplete(value, unknownSymbols)` preserves recognized/numeric bits while
making every unknown token explicit; the compatibility `openFlags` projection is `null` for an
incomplete decode so policy compilation remains fail-closed.

`TierEOptions` adds `maxPendingRecords` and `maxRetainedRecords`, both defaulting to 100,000. New
records beyond either bound are discarded and included in session loss accounting, making
`SessionIntegrity.guarantee` incomplete. `TierEProfilerSession.finish()` reads terminal definitions
once and returns the same cached immutable `TierEProfile` on subsequent calls. `poll()` is illegal
after finish or close.

The two former `LiveEbpfCollector` implementations now have responsibility-specific names:

- `io.mazewall.profiler.collector.ProfileObservationEbpfCollector` translates architecture-aware
  syscall records into `ProfileObservation` values.
- `io.mazewall.profiler.tierE.collector.TierERingBufferCollector` retains raw Tier E ring records.

Both old class names remain as deprecated forwarding shims for source migration.
