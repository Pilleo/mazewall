# Checked metadata watermark retirement

Scoped remedy for the error propagation finding in
[ownership/loss review](ownership-loss-review.md), based on `918c5d60`.
The pinned profiler, native ABI, session/worker/buffer bounds and loss claim
boundaries remain the same. The authored patch is applied with zero fuzz to the
checksum-verified generated dependency, with no module-cache modifications.

## Ownership and error contract

The additive `ProcessedUntilChecked` returns joined, wrapped interpreter detach
errors. Only successfully detached instances are removed from the ownership map.
Failed instances retain their process metadata and exit watermark. An explicit
retry reattempts failed instances, without redetaching successful instances.
Processes whose exit is later than the watermark are untouched.

Strict fatal notification occurs after releasing the process-manager mutex;
the callback may acquire that mutex and abort the collector's owned lifetime.
The original void `ProcessedUntil` signature remains available and delegates to
the same checked retirement, retaining diagnostic logging for its old callers.

Both original perf-reader watermark branches call the checked boundary. On
failure the reader preserves the error and exits without inventing an explicit
flush. `WaitTraceReader` returns the owned reader's stored error after joining,
so ordinary cancellation joins also cannot hide metadata/reader retirement
failure. The existing strict drain owner propagates this error and cannot mark
successful buffer completion or permit checked resource retirement.

This fixes the identified watermark boundary. It does not certify every auxiliary
monitor join, every interpreter's internal partial-detach implementation, all
native definition submission losses, or universal metadata retirement.

## Red/green evidence

Before the fix, the actual `ProcessedUntil` regression logged EPERM/EACCES but
delivered no strict fatal cause. A separate reader-join regression returned nil
despite stored EPERM/EACCES. Both failed for the intended behavior before their
corrections.

Passing race tests cover:

- Future exit watermarks are not prematurely retired.
- EPERM and EACCES remain recognizable through `errors.Is`.
- Multiple native failures retain both causes.
- Failed interpreter/process-info ownership survives; successful owners are
  removed individually and not detached twice during explicit retry.
- Strict failure callbacks execute after metadata unlocking.
- The checked API returns native errors even without a configured fatal callback.
- Reader joins preserve native failures instead of reporting nil.

Blast-radius tools returned `PARTIAL`/`CODANNA_UNAVAILABLE`; direct source/caller
inspection supplied the actual watermark and join evidence.

## Verification and live controls

`make verify` and race-enabled native event/definition finalization targets pass
on the final build. The native definition gate retains 8/8 and 5000/5000 queued
records. Metadata permission failure is injected at the interpreter interface in
host tests; the native transport gate is a separate control, not a claim of a
kernel-induced metadata permission fault.

Initial checked build `28055699f5d347e7338bfec3dc842f846c05a03b27abd1a87d0152690e1bf632`:
`benchmark.109f8386` correctly attributes all 1200 measured segments in each of
three paced strict-full repeats, with zero missing/wrong/unavailable/skipped
measured outcomes. Strict unloading gate `capture.lCAloQ` also passes.

Final bundle after reader-join error correction:
`1d94a585f9754407351900d17b482952c15b40977b14ced82fb3c9405490bd85`.
Collector binary SHA-256:
`b46ba6b852bf5ab5ef8d6bc744474ae95096531d386a30f8808cee3908cce591`.
Final live control `benchmark.7d3e6251` correctly attributes all 200 measured
segments with acknowledged definition drain and no missing/wrong/skipped/
unavailable measured outcomes. Complete loss remains false.
The default legacy structured-capture integration gate also passes on the final
build (`capture.6BCGFf`).

The repository merge gate initially failed an unchanged JVM process-wide profiler
integration observation with ACK errno 32. Its unchanged focused retry passed,
then `./gradlew build` passed. The intermittent failure is tracked separately;
no JVM profiler fix or root-cause claim is bundled here.
