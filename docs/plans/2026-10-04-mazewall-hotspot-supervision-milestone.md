# Owned HotSpot Kotlin collector supervision — milestone 4

`HotSpotBackend` now verifies installed version-2 assets, observes target birth
generation through `HotSpotTargetIdentity`, and reuses the existing
`ProcessLauncher.startProcess(argv, redirectErrorStream=false)`. The configured
operator prefix is copied as an immutable argv vector. There is no shell expansion,
runtime build/download, automatic sudo or parent eBPF-authority admission.
The actual helper admits runtime and privileges through the validated handshake.

The ownership session remains internal, consistent with the earlier internal
evidence contracts. Public `HotSpotProfilingSession`/result/strategy exposure stays
in the milestone-5 API review; existing public descriptors and AUTO/USER_NOTIF/
recorded-EBPF behavior are unchanged. Platform traits and native layouts are unchanged.
Existing `ChildStartupTransaction` owns JVM/Unix-endpoint/line-pump resources and
does not provide the separate machine/stdout/stderr/parent-control ownership needed
here; this adapter reuses ProcessLauncher with focused external-collector leases.

## Ownership and lifecycle

The child is acquired asynchronously. Launch itself remains a retained lease until
return; a timeout before the Process exists cannot close or signal an invented PID.
A late acquired child is owned first, then aborted. Platform daemon workers own
stdout JSONL, independently drained stderr, process wait and finite timer. One
stop worker writes stop newline once; stdin stays open until the actual child exits.
Abort signals only the acquired Process. Configured wrappers must forward control/
EOF and wait for the actual collector, including aborts; a wrapper exit alone is
not evidence that untracked descendants were retired.

No I/O occurs under the synchronized domain store. The pure machine evaluates
states/effects; the store commits those decisions and the driver schedules effects.
Completion adds a resources-joined fact: native summary, stdout EOF, stderr EOF and
zero child exit cannot hide an active control/launch/retirement worker or failed
pipe close. Only actual thread termination/join and successful owned pipe closure
acknowledge that fact. Independent close failures retain their own owners and keep
other cleanup attempts active. Explicit retry releases successful resources but
does not convert a failed session into success.

Startup/session/stop bounds remain at most 10/60/2 seconds, measured by nanoTime;
tests can select smaller verified bounds. Concurrent finish/close shares one
graceful stop and the immutable successful result. Interruptions preserve the
original InterruptedException and restore caller interrupt status after bounded
abort/retirement. `HotSpotSessionFailure` exposes immutable partial evidence and
the actual retained owner; pending ownership is never hidden by a timeout/EOF.

## Streaming and diagnostics

The existing bounded assembler now exposes a streaming accumulator. Partial
snapshots include immutable completed pairs, separate pending occurrence counts,
observed counters and honest retention loss. Pending pairs are not falsely counted
as discarded retention. Final pairing/counters still require the terminal summary;
partial snapshots cannot certify terminal accounting or completion. Retention caps
continue draining/terminal accounting without retaining excess payloads.

Stderr retains at most 64 KiB and records discarded byte counts while continuing
to drain. It is never merged into machine JSONL. Existing line/depth/frame/record
limits remain in force. Byte retention is wire-payload accounting, not an exact
JVM heap bound. Native loss and enforcement coverage remain incomplete.

The first real supervision path exposed a prototype bug: the assembler stores the
summary object itself, while `HotSpotProtocol.summaryEvent` tried to parse a wire
envelope around it. A dedicated test observed that failure before correcting the
parser. Resource-joined pure-state and streamed-evidence contracts also observed
their intended red missing implementation failures.

## Verification

- Four streaming, five real child-process, five lifecycle/fault and three backend
  contracts cover immutable snapshots, retention accounting, missing assets,
  split pipes, native permission refusal, bad generation, early exit, malformed
  output, ready/stop timeout, stderr flood, late process acquisition, held stderr,
  duplicate finish, caller interruption and AccessDenied close/retry ownership.
- The test-only Java helper creates real OS processes/pipes/exit behavior and
  synthetic protocol records. It does not install probes or claim native fidelity.
  Real reader/launcher/close fault injection wraps only actually acquired child
  handles/streams. Tight timeout applies to deliberately missing readiness; valid
  JVM startup keeps the supported default bound to avoid timing-flaky tests.
- Installed assets and operator argv preservation pass. The actual external
  collector refuses native errno 1 on the ordinary host parent; typed cause and
  all child/pipe retirement are preserved through the Kotlin backend.
- Explicit guarded `HotSpotNativeAttachTest` passes against a fresh ordinary-user
  pinned Temurin25 interpreter/Serial JVM and privileged external installed
  collector, launched with a cached-image operator Docker prefix. Kotlin waits
  for real native ready, opens the fixture gate, captures **400 marked occurrence
  pairs with genuine InlineOpenFixture frames**, sends parent-pipe stop, verifies
  complete delivered-terminal accounting/native drains and retires its workers/
  pipes. Loss remains partial. This uses the existing tested Go helper/OCI assets;
  no Go/BPF source or fingerprint changes occur in this milestone.
- All HotSpot contracts, full profiler tests, production Detekt/warning ratchet,
  closed-set architecture guard and `./gradlew build` pass. Guarded kernel/installed
  tests are explicitly enabled for their acceptance runs and remain optional in
  ordinary host unit builds. Backlog/index and whitespace gates complete the scope.

Next: milestone 5 exposes the explicit strategy, compatible result ownership,
public options/session surface and checked workload facade. General JIT, broader
syscalls, virtual-thread logical identity and complete native loss remain later gates.
