# Owned auxiliary worker retirement

Scoped implementation of the finding characterized at `d96651b6`. Strict full
finalization now joins auxiliary work before retiring the event collection, while
keeping definition readout, identity guards and enrichment on their independent
owned context. This is scoped lifecycle evidence, not independent approval or
complete native loss accounting.

## Ownership and ordering

The strict profiler creates an opt-in context-owned scope with sealed goroutine
and close/wake enrollment. It tracks the existing periodic callbacks, PID worker,
report-event perf reader, kernel-symbol reload/poll workers, deferred executable
cleanup and interpreter metrics, plus native async map-update workers. Contexts
without this scope retain ordinary periodiccaller APIs and behavior.

Stop seals admission, cancels only auxiliary work, performs checked wake actions,
joins registered workers and performs post-join descriptor finalizers. A pending
callback or failed native close cannot authorize retirement. Caller timeout does
not bypass the background owner or cancel the definition/enrichment parent.
Native failures retain their wrapped causes; unexpected read failures are fatal
even when cancellation races them. Only typed expected canceled-read wake errors
are excluded from failure accounting.

PID-map sends select cancellation. Native async update admission is serialized
with final queue cleanup, so canceled producers cannot enqueue owned inner-map
descriptors after worker shutdown. Queued deletions own no inner descriptor and
are handled without a fabricated close token. Rejected/queued owned inner maps
are closed with checked strict error propagation.

Gadget's lazy prune worker is also sealed and joined. An unstarted prune loop
does not invent a pending acknowledgment; a held prune callback prevents completion.
The strict full stop hook orders definition drain/final consumption, enrichment
finish, prune/auxiliary joins, identity checks and then event-resource retirement,
all under its existing remaining stop budget. Resolver/tracer Close enforce the
join gate too. The additive `auxiliary_stopped` observation is required by the
strict full benchmark validator and does not imply auxiliary buffers drained.

## Native netlink wake correction

A stricter read-error policy exposed a real rejected shutdown control: closing
an idle kobject raw socket wakes `RawConn.Read` with Go's private `use of closed
file` error, which is not the public `os.ErrClosed` sentinel. The collector failed
explicitly rather than treating arbitrary errors as successful cancellation.
Source-labeled diagnostics localized this to `auxiliary kobject read`.

The remedy avoids string matching or broad error suppression: set the socket's
read deadline to wake its poller, join the reload/poll workers, and close its owned
socket only in a checked post-join finalizer. The expected public deadline error
is recognizable; EPERM/EACCES and other native failures remain errors. Rejected
diagnostic artifacts, including `benchmark.2c6bfbd8`, are not acceptance evidence.

## Behavioral and native verification

Initial regressions failed because the tracer lacked a checked auxiliary stop
boundary and a strict report accepted unjoined auxiliary workers. A further red
regression showed cancellation masking an actual permission cause. Passing race
tests now cover:

- Held callback timeout, sealed post-stop admission and parent-lifetime isolation.
- Wake/close cause aggregation, EPERM/EACCES/ENOMEM preservation under cancellation.
- Descriptor finalizers executing only after worker acknowledgment.
- Actual held Gadget prune callbacks and unstarted/later prune admission.
- Blocked async producers, canceled deletion cleanup and joined update workers.
- Strict full report rejection when auxiliary completion is absent.

Race-enabled kernel controls additionally verify a real report-event perf reader:
native token 42 reaches a held callback, its reader closes/wakes, the borrowed map
remains valid, and release permits checked join. The definition/enrichment parent
stays alive. A separate real idle kobject monitor joins after deadline wake and
post-join socket retirement. Existing 8/8 and 5000/5000 definition tails and event
deadline/resource-retention gates still pass.

`make verify`, both native finalization targets, and `./gradlew build` pass.
Impact lookup was PARTIAL/CODANNA_UNAVAILABLE; enrollment uses direct pinned
source/caller inspection. Production Tier E/FFM/USER_NOTIF code is outside this fix.

## Live controls and reporter freshness

Final bundle:
`e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb`.
Collector SHA-256:
`b440574d672eb7b6a0ca15d4e3bf0fd2eb80c6062878f79fe49e5ecf23be8ff0`.
Current report binary SHA-256:
`83849573525c53d9270e01ccfeac9b85462ace7120e8454e601c55436a0ee3f0`.
Dependency/runtime/kernel pins remain those of the prior strict profile.

`benchmark.ab5812c8`: three rotated baseline/full, eight-route repeats, 1200
measured operations, 600/600 preparation and 500000 ns requested pacing. Each
full repeat correctly attributes all 1200 operations with zero measured wrong,
missing, unavailable or skipped outcomes. Both native transport drains and
`auxiliary_stopped=true` are present in freshly generated reports. Post-target
stop observations: 1256604520/1262062003/1416861697 ns. Maximum pending counts:
307/274/238; pending payload bytes: 201615/180234/156420. Complete loss stays false.

`capture.nk6suL`: strict disposable-loader attribution gate passes, including
1580 correct resolved segments; session counts retain 1761 records, 181 unavailable
and zero skipped. Definition and auxiliary completion are acknowledged.

`capture.gTCnWw`: genuine first-definition-drop recovery gate passes: 400 terminals,
349 resolved/51 unavailable, one real invalidation and zero recovery errors.
Subsequent occurrences recover; original unavailable records remain immutable.
Definition and auxiliary completion are acknowledged.

`capture.wfBZsw`: default legacy structured-capture gate passes on the final build.

Before the reboot checkpoint, the runner used a stale report executable that
omitted the new field despite raw collector summaries containing it. The shell
runner now rebuilds `benchmark-report` before measuring. Saved six-repeat artifacts
`benchmark.c430f334` and `benchmark.34ca58e1` were revalidated with the current
auxiliary-aware analyzer into separate `run-report.auxiliary-verified.json` files;
historical reports were not overwritten. Final acceptance above uses fresh reports.

## Remaining claim boundaries

Joined auxiliary work does not establish complete definition submission/decode
loss, an auxiliary notification-buffer drain, every native map/program FD's
retirement, or arbitrary concurrent external attachment/Close support. The owned
scope is admitted only for the pinned strict epoch profile. Broader JVM/JIT/BCI
fidelity, universal syscall support, long-running capacity and independent review
remain distinct gates before general Kubescape integration.
