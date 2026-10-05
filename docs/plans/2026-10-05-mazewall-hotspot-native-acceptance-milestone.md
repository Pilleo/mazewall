# Public HotSpot native acceptance — milestone 6

The complete guarded public integration class ran on Linux, pinned Temurin25
`-Xint`/SerialGC and the local rootful Docker host with the version-2 installed
asset bundle. Rootful endpoint aliases were aligned explicitly after the first
JUnit condition correctly skipped conflicting host-vs-user Docker URI settings.
Final `HotSpotPublicIntegrationTest.xml`: **6 tests, 0 skipped, 0 failed**.

## Public-path acceptance

The unique-token fixture creates 400 distinct `hs-<run>-<sequence>-A/B` files,
with two known Java caller paths and deterministic sequence. Public attach waits for
native ready; 400 warmups and every measured token finish before requesting drain.
The test correlates the event's own occurrence/generation/terminal—not nearest TID
or recaptured stack—and checks exact leaf-to-root Java method order for every
attributed A/B token. Other outcomes must have empty frames and explicit
unavailable/skipped status. Full session delivery was 802 event terminals (the 400
measured opens plus fixture/support work); all measured tokens had ordered useful
segments in the accepted public run. Physical event/definition/auxiliary joins pass;
loss/coverage remain incomplete.

The independent installed Kotlin wrapper and standalone collector use three
serially alternating matched fixture pairs, each 400 token opens paced at 1 ms;
both report the same checksum (1200). Each target reports its actual target-only
monotonic window. The retained schedule is
`profiler/build/reports/hotspot-acceptance/matched-f532cf9f-8f23-4efb-b036-1f787b4151c7.json`.

| Repeat | Public target window | Standalone target window |
|---:|---:|---:|
| 0 | 567,618,336 ns | 536,003,855 ns |
| 1 | 517,902,050 ns | 523,306,929 ns |
| 2 | 488,999,462 ns | 502,667,985 ns |

These are measured windows, **not an acceptance threshold**. The duration excludes
collector startup/readiness; independent native evidence accompanies each profile.

Other completed cases:

- Retention cap one: 502 delivered terminals, one retained, 501 explicitly
  discarded, zero pending, terminal accounting complete, collection incomplete.
- Actual unprivileged external helper: native EPERM/EACCES preserved, no ready or
  user workload, child/pipes joined.
- Deliberately short readiness deadline: no usable session, acquired helper retired.
- Target killed/restarted: original session fails and never attributes a later PID/
  generation to old events; cleanup owner retires.
- Same public wrapper in a supported current interpreter/Serial JVM returns the
  lambda value and useful current-class evidence. Captured OS UID is1000. The normal
  host JVM (JIT/unsupported) is refused before the block, with no fallback.
- Original-event expiry control: separate live schema-1 unknown-identity experiment
  selected a1ns lookup budget; all 400 marked events expired with empty frames. An
  attempted schema-2 strict variant was refused because its patched definition
  lookup detected a native definition-task identity mismatch. No production native
  deadline/budget was changed to force this case; the rejected log remains at
  `experiments/hotspot-agentless/build/capture.NisTWL`.
- Fresh native gates pass: genuine lost-first-definition recovery,
  disposable-class-loader unloading, same-PID exec barrier, owned definition
  8/5000 callback tails/auxiliary perf/netlink, and event callback detach/drain.
  They use the actual pinned graph/patch fingerprint; no failure of a strict
  control is described as successful attribution.

## Investigation / limits

An initial dense current-interpreter experiment expired during its unsupported
600-operation/1ms-profiled workload: asset+collector admission took 34–82s under
`-Xint`; self-load reached the strict stop deadline with only153–298 of600 events
read and pending pairs retained. The ordinary JIT host is a separate early
refusal, not this measurement. That initial test exposed scalar `InputStream.read()`
per pipe byte. A red test required block reads, then `HotSpotWireReader` switched
to bounded buffered reads. The supported current-JVM facade acceptance was paced
to avoid implying a guaranteed high-rate self-profiling bound. No native buffers,
session/stop deadlines or claims were relaxed. Reproduced config/decoder overhead
is tracked separately in
[`issue-20261004-193054-174`](../internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md).

One attempt to use strict safepoint-epoch on a second wrapper target failed with
`stack definition task identity mismatch`; no fallback frames were accepted. The
passing strict corpus uses the supported pinned ordinary-user JVM/generation.
Kernel/OCI-enabled tests are explicitly gated and remain absent from ordinary
unit/build runs.

The full project `./gradlew build`, full profiler unit suite, public API/legacy ABI
checks, production Detekt/warning ratchet, Go experiment `make verify`, native
ownership/recovery/unloading/exec gates and all six public native integration
cases pass. Standalone `:profiler:detektIntegrationTest` still fails on pre-existing
issues in shared `TestSuiteHealthListener.kt` and
`ContainerEndpointDiscovery.kt`; no integration source findings remain in the new
HotSpot files after named fixture constants/class-local natural-language test-name
suppression. No updates were made to those shared user-owned files.

The installed helper/gadget/ABI fingerprints are unchanged. The accepted evidence
continues to mark whole-session loss unknown/incomplete and policy conversion
refuses all HotSpot results.
