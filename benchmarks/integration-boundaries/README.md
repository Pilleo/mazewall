# Bounded integration-boundary checking harness

This separate published-artifact consumer compares equivalent ASCII conversion
on the calling thread, one prestarted dedicated JDK platform worker, one actual
`ManagedRuntime` worker, and the first-party packaged `bounded-text` Portal service.
It uses public `PreparedPortalOperation` and `PortalInvocationTiming` through
`PortalRuntime.health()`. The host reproduces the reviewed ASCII algorithm and
checks each accepted output against an independent ASCII-input oracle; it never
loads the guest implementation. An explicit negative class-loading check confirms
the opaque worker is absent from the host classpath.

This is a developer checking harness, not a performance acceptance gate or an ACE
boundary proof. Task6c owns the full raw matrix after the new final candidate.
The current checking coordinate is WIP, not a committed producer or final release.

## Exact build/run recipe

Use a new local publication repository and a source-hash-bound WIP coordinate for
checking; a final candidate must instead use its actual new committed producer.
The existing [two-stage Koog bootstrap](../../scripts/koog-build.sh) and
[consumer staging init script](../../tests/consumers/stage.init.gradle) publish
the actual producer modules. Never overwrite historical coordinates or locks.
All Gradle/native/publication commands acquire the shared lock. These commands
are externally locked; do not wrap an internally locking consumer script again.

```bash
cd /home/leanid/Documents/code/java/jseccomp/.worktrees/integration-contract-completion-20261011
export JAVA_HOME=/home/leanid/.sdkman/candidates/java/25.4.4+1-graal
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-09-opencode-v1-zed-continuation/gradle-hook-home
lock=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock
out=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/task-6a
version=0.1.0-task6a-wip.4eb86a85fe82dda3
repository="$out/checking-repository"

# Publication was already performed once for this exact checking source inventory.
# For a NEW source identity, select a NEW version/repository before publication:
flock -w 600 "$lock" timeout --kill-after=30s 900s ./scripts/koog-build.sh \
  --console=plain --no-build-cache --init-script tests/consumers/stage.init.gradle \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" -- \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository \
  :portals:runtime:publishAllPublicationsToConsumerBaselineRepository \
  :portals:worker:publishAllPublicationsToConsumerBaselineRepository \
  :integrations:koog:publishAllPublicationsToConsumerBaselineRepository \
  :demos:koog-contained-tool:service-api:publishAllPublicationsToConsumerBaselineRepository \
  :demos:koog-contained-tool:publishAllPublicationsToConsumerBaselineRepository

# Add --write-locks only ONCE when generating a new coordinate's reviewed locks.
flock -w 600 "$lock" timeout --kill-after=30s 600s ./gradlew \
  -p benchmarks/integration-boundaries --console=plain \
  --no-configuration-cache --no-build-cache \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  verifyPublishedClosure installDist

cp_path='benchmarks/integration-boundaries/build/install/integration-boundaries/lib/*'
flock -w 600 "$lock" timeout --kill-after=30s 30s "$JAVA_HOME/bin/java" \
  --enable-native-access=ALL-UNNAMED -cp "$cp_path" \
  io.mazewall.benchmark.BoundariesKt --selfcheck

# cache must be a NEW nonexistent directory for each cold preparation run.
flock -w 600 "$lock" timeout --kill-after=30s 120s "$JAVA_HOME/bin/java" \
  --enable-native-access=ALL-UNNAMED -Dbenchmark.cache="$out/new-run-worker-cache" \
  -cp "$cp_path" io.mazewall.benchmark.BoundariesKt \
  --samples 3 --size 1024 --pressure 4 --seconds 60 --concurrency 1 \
  >"$out/new-run.raw.log" 2>&1
# Requires the coordinate's separately verified frozen publication-custody manifest.
python3 "$out/publication_custody.py" verify-resolution
python3 benchmarks/integration-boundaries/check_raw.py --selfcheck \
  --custody "$out/fixround1-publication-freeze/manifest.json" "$out/new-run.raw.log"
```

Keep actual argv/log/exit receipts, source/JAR/embedded worker hashes, strict-lock
hashes and acquired process-end evidence beside the raw log. `verifyPublishedClosure`
requires the exact six host module JARs in the explicit repository at the selected
coordinate and rejects project/source substitution. The worker is embedded opaque
bundle data, not a host dependency. Worker/bootstrap and profiler classes are built
into that verified bundle; they are not separate host publications in this recipe
(the selected worker publication task has no publications). Build outputs are disposable;
evidence belongs in the supplied evidence directory.

The checking coordinate binds the **producer module/build input inventory**, not
the benchmark validator's source. Validator/docs revisions have a separate task-source
inventory and do not rename unchanged published modules or the native benchmark JAR.
Fixround1 freezes all six versioned POMs and six Gradle `.module` files, with current
equality to generated publication outputs. Refreshed strict compile/runtime resolution,
selected dependency edges/artifact hashes and the carried installed classpath are
then captured and checked unchanged through a second minimal strict acceptance.
That metadata freeze starts at fixround1: it does **not** claim unrecorded equality
at the original native smoke's start. Original source/JAR/worker/lock/physical receipts
are retained and the unchanged native executable is carried, not re-run.

`--custody` is required by the raw checker and binds the raw worker-bundle identity
to the expected bundle hash in the frozen manifest. It does not authenticate the
manifest itself or the repository. Verify companion publication/strict-resolution
custody first; the raw checker validates observations, not publication provenance.
For a new producer/candidate, create a new coordinate-specific freeze and corresponding
resolution receipts before acceptance. The Task6a evidence scripts/constants refer
to this exact WIP coordinate and cannot stand in for a new producer's freeze.

## Bounds and ownership

The CLI accepts unique `--name integer` pairs: `--samples` 2–1000 (warm samples,
plus one first-measured sample), `--size` 1–65536 ASCII bytes, `--pressure` 3–64,
`--seconds` 5–600, and `--concurrency` exactly 1. Defaults are 8/1024/4/60/1.
The packaged service is a stateful singleton: it cannot establish equivalent
parallel guest work, so higher concurrency is rejected rather than misrepresented.
Unknown/duplicate/odd/noninteger/out-of-range parameters fail before native setup.
Selfchecks cover Unicode preservation, maximum input, UTF-8 rejection, CLI bounds,
actual queue overload, timed wait, budget exhaustion and joined executable work.

`--seconds` is the measured-work absolute wait/admission budget, beginning before
setup. Native open/preparation/startup each have independent finite 30-second
bounds; teardown has finite 10-second bounds. The outer command budget must leave
room for setup and cleanup; a killed outer JVM is a failed run, never valid evidence.
No accepted result is considered accounted until its body-end/physical-end
observation is checked. All owned APIs retain their required fail-closed install
and readiness checks. Every resource owner is closed on failure; original failures
retain cleanup failures as suppressed causes. Incomplete cleanup fails the run.

The JDK worker is acquired and prestarted on the trusted caller before any narrower
worker domain, retains its actual Thread owner, and is joined on teardown. No
replacement thread, lazy Landlock inheritance, caller-runs/shared-worker fallback
or replay is used. Managed drain requires public `DrainReport.Complete`; Portal
close and actual acquired `ProcessHandle.isAlive == false` are both checked.
Post-drain JDK/Managed submissions reject and Portal returns `NotStarted`.

The host explicitly installs a permanent launch-compatible Tier1 denying PTRACE,
MOUNT, UNSHARE and CHROOT with HOTSPOT_JIT executable mappings. It does not install
host Landlock or a no-exec baseline that would prevent Portal child launch. Managed
body policy adds filesystem restriction with existing JVM-classpath grants and
exec denial; the packaged child retains its reviewed required static baseline.
This is not identical security policy across modes. The workload is the same.
Environment and credential authority remain the existing operator-owned launch
contract; the harness does not read, enumerate or change environment variables.

## Raw TSV schema (version 1)

All durations/clocks are monotonic nanoseconds unless explicitly named otherwise.
Non-TSV diagnostic lines remain in the raw log (including native security messages).
No measurements are inferred from those messages.

| Record | Fields after tag |
|---|---|
| `schema` | version, clock unit, checking scope |
| `environment` | actual Java runtime version, vendor, OS, architecture, bounded options |
| `prepare` | mode, elapsed ns, **aggregate scope label** |
| `worker-artifact` | manifest artifact key, actual bundle SHA256 |
| `resources` | label, observed PID, proc VmRSS (kB), proc Threads, **host** heap-used bytes, **host** MXBean live threads |
| `sample` | mode, phase, input ID, UTF-8 bytes, input SHA256, output SHA256, submitted clock, body/mapping start, body/mapping end, result-observed clock, end-observed clock, submit-to-start ns, body/mapping ns, attribution label |
| `portal-timing` | phase, input ID, public broker queue-wait ns, public transport+worker ns, attribution label |
| `rejected` | mode, pressure phase, input ID, ASCII bytes, input SHA256, rejection form |
| `throughput` | mode, ordinary completions, batch elapsed ns, scope label |
| `accounting` | mode, offered, accepted, rejected, completed |
| `drain` | mode, elapsed ns, actual outcome (direct has no asynchronous owner) |
| `post-drain` | mode, actual rejection/non-start outcome |
| `physical-child-end` | acquired PID, actual alive Boolean (false required) |
| `physical-thread-end` | JDK, acquired thread ID, actual alive Boolean (false required) |
| `unavailable` | isolated metrics unavailable through current public APIs |
| `selfcheck` / `checking-smoke` | PASS only after actual assertions |

`first-measured` is **not** a claim of uninitialized/JIT-cold local code: selfchecks
and preload already ran. Cold bundle materialization/startup/readiness aggregates
use a fresh cache. `warm` means subsequent calls in that same JVM/session, not a
claim of steady-state optimized machine code. Retain repeated raw samples.

Submit-to-start includes host submission, queueing and dispatch; it is not isolated
queue or dispatch overhead. JDK/Managed body clocks enclose the local operation;
Portal clocks enclose host mapping plus transport/worker. Its public
`workerExecution` is a transport+worker aggregate, **not** guest-body time or a
separate IPC estimate. Binding preparation includes materialization, startup,
required native installation and readiness; no native-install split is invented.
Portal last-invocation timing is attributed only after serial ordinary completion;
it is intentionally unavailable per pressured concurrent outstanding handle.

Pressure uses the same bounded inputs but holds the first asynchronous host/body
entry on an explicit test latch while filling two admission slots. The gate is
outside Portal guest code. Offered=accepted+rejected and accepted=completed are
checked; all accepted outputs are verified. The direct mode completes synchronous
offers without an admission gate/rejections. This is an admission-control fixture,
not an equivalent-completion latency distribution. Never pool pressure and ordinary
samples. The raw validator separately summarizes ordinary warm min/median/max and
ordinary **harness throughput including verification/hash/raw output**. Acceptance
requires exact schema/tag/field shapes, bounded environment options, every configured
input ID/size/phase, actual expected rejection forms/counts, honest attribution labels,
all preparation/resource/unavailable/identity/selfcheck families, successful drain
and post-drain refusal outcomes, and one-to-one phase/ID joins for ordinary Portal
timings. Broker queue plus transport/worker duration must fit its observed mapping
interval. Unknown tags/modes, duplicate timing IDs, missing families and false isolated
labels reject. Non-TSV native diagnostic lines remain retained but are not measurements.

The mutation selfcheck separately corrupts attribution, durations, outcomes, configured
workload, bundle identity, digests and clocks; removes each family and individual
preparation/resource row; duplicates/mismatches Portal timing IDs; and tests physically
live owners. Each case must reject for its expected reason while the genuine log
remains accepted. The metadata companion independently tests same-coordinate POM/module
whitespace replacements and missing files in acquired temporary copies, leaving the
real checking repository untouched. These controls require no new native smoke.

RSS/threads are process snapshots, not allocations or isolated boundary overhead.
All owners coexist in one JVM and modes run in fixed order; native state, JIT,
GC, logging, verification and proc reads influence observations. Worker snapshots
measure the actual child; their host heap/MXBean fields still describe the host.
No percent-overhead, threshold, negligible-cost or performance-ranking claim follows.

Related contracts: original R1 in the operator-owned read-only ROOT input
`integrations/2026-10-06-mazewall-integration-foundation-plan.md` (not a worktree source file),
[Portal isolation/diagnostics](../../docs/internals/designs/portal/local-service-isolation-design.md#diagnostics-and-failure-evidence),
[consumer publication patterns](../../tests/consumers/README.md),
[Task6a issue](../../docs/internals/backlog/issue-20261012-062010-859-add-bounded-equivalent-work-integration-boundary-c.md).
