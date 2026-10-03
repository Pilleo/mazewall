# Strict full ownership and loss-domain source checkpoint

Reviewed local strict-full milestone `23c2a8bd` after the measurements recorded
through `acd95562`. This is a targeted self-review, not independent sign-off or a
whole-project audit. The source set is the committed additive Gadget/profiler
patches and their exact generated dependency bundle
`d4928c88f551b0da76b95a9e68bf4729fe9f5d39a5bcf3a79e6e93ef107cfab1`.

## Confirmed error-propagation gap — high priority

The pinned profiler's `ProcessManager.ProcessedUntil`:

1. Calls interpreter `Detach` during metadata watermark cleanup.
2. Formats each failure using `%v`, losing the wrapped native error identity.
3. Joins failures locally and logs them in a defer, without returning an error.
4. Deletes interpreter and exit-event entries even when detach failed.

Source: `processmanager/processinfo.go:751-784` in pinned profiler `ef6cf29492c8`.
The selected generated source retains this behavior. The strict perf reader still
calls this void method at `tracer/events.go:296,299`; its definition-drain error
control has no path to receive these failures. In particular, EPERM/EACCES at this
cleanup boundary cannot be detected through the returned strict drain error.

This finding does not invalidate the explicit perf flush/consumer acknowledgment
or measured Java attribution. It blocks a stronger claim of checked metadata
retirement/error coverage. No live permission fault has been injected into this
path yet, and no fix is claimed. The next scoped task is an additive checked
watermark boundary, preservation of native causes and failed ownership, and a
behavioral failure-injection regression before wiring it into strict readout.

Tracked in [metadata retirement issue](../../../docs/internals/backlog/resolved/issue-20261003-123138-580-preserve-native-metadata-retirement-failures-in-ag.md).
The subsequent [checked watermark remedy](metadata-retirement.md) resolves this
specific finding with red/green fault regressions and retained failed ownership.
The source observations above describe the reviewed pre-remedy milestone.

## Ownership boundaries verified by inspection

- `definitionDrainOwner` serializes its stop operation with `sync.Once`. Its
  backend orders source quiescence, original reader drain, then consumer join.
  `BuffersDrained` is set only after both steps and a final context check.
- `DrainTraceReader` refuses unstarted/non-quiesced sources and distinguishes
  stopping from observing an explicit perf flush boundary. Its error accumulator
  now preserves multiple native causes with `errors.Join`.
- `QuiesceDefinitionSources` rejects attached sampling entrypoints and closes
  owned profiler hooks without closing process metadata. Its hook-map mutation
  assumes the supported initialized single-owner lifecycle; concurrent attachment
  or an external direct tracer Close is not certified by this checkpoint.
- The collector's graceful request lifetime is distinct from definition/identity/
  enrichment ownership. Fatal state cancels both. Pending failed full owners retain
  the exec-barrier lease rather than silently declaring checked retirement.

The original metrics, PID/process monitors and process-manager Close deserve a
separate lifecycle audit before in-process integration. Joining the definition
reader/consumer alone is not evidence that every auxiliary goroutine is joined.

## Loss domains remain separate

The native openat-ring reserve snapshot covers its observed reservation failures.
Definition consumed perf loss/read errors cover what the reader observed. Neither
accounts for every native definition submission, decoding failure, process
metadata cleanup failure, admission skip or unavailable walk. Explicit buffer
completion is therefore independent of complete native loss, whole-session
resource retirement and semantic frame/BCI fidelity.

The existing report fields correctly keep complete loss false. Preserve that
boundary while addressing the confirmed metadata error gap. Independent review
and the Kubescape dependency/event/storage contract remain subsequent gates.
