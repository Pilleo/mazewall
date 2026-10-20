# HotSpot upstream-alignment specification

## Baseline and authority

Baseline: `8da8b4efcc0b72d9c4f06cf798e6daee0d9b1120` in the existing authorized
`hotspot-review-remediation` worktree. This is a planning supplement to
[the remaining-work specification](2026-10-17-hotspot-remaining-work-spec.md),
not approval to activate native interfaces, runtime profiles or new asset pins.

The Elastic-origin repository redirects to
`open-telemetry/opentelemetry-ebpf-profiler`. The comparison inspected our pinned
`ef6cf29492c8a980f889052903fa2dd0c0765cc0` and upstream
`36f875fd7d625a80de939a9325e1cde8fd43a9f6` (2026-10-16). Context7 did not identify
the profiler itself; the official source at those exact commits is the authority.
Go overlay documentation was retrieved for Go1.26.7; check the installed Go1.26.8
`go help build` contract when executing. This does not change either toolchain pin.

## Project-wide requirements copied into the plans

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Production remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry` until a separately approved capability gate passes.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Raw FFM remains behind NativeEngine/io.mazewall.ffi; confined arenas, immediate errno capture, acquired descriptor ownership and checked close.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Never install seccomp from a virtual thread or change JVM coordination syscall floors; do not touch GITHUB_TOKEN.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

## A: Reuse the generation check that already exists

The pinned BPF HotSpot unwinder stores `nmethod._compile_id` in the frame's
`ptr_check`; `calc_line` packs it into the low32bits of the line word. The pinned
Go decoder extracts that word and `getJITInfo` accepts a cache entry only when its
compile ID matches, otherwise reads the nmethod and rejects a changed ID. The
prepared local patched source retains this behavior.

Sources:

- [Pinned capture cookie](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/ef6cf29492c8a980f889052903fa2dd0c0765cc0/support/ebpf/hotspot_tracer.ebpf.c#L577-L589).
- [Pinned decoder/cache check](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/ef6cf29492c8a980f889052903fa2dd0c0765cc0/interpreter/hotspot/instance.go#L401-L437).
- Local `patches/profiler-additions/interpreter/hotspot/epoch.go.txt:119-145`
  deliberately refuses the compiled subtype in the strict epoch path.

Replace the unexecuted Plan05 claim that `compiled_generation` is absent.
Characterize the actual prepared upstream function, including cache hit, stale
cache and fresh-ID mismatch. Use a Go build overlay for test-only access to the
existing unexported function: do not modify generated dependencies/module cache,
production patches, BPF layouts or accepted patch618c just to add host tests.

The resulting audit describes the **reviewed pinned source contract**, keyed by
capture ABI, exact upstream revision and exact local patch digest. It reports
existing compile-ID capture/validation separately from unproved metadata stability,
code lifetime and concurrent-reclamation exclusion. Its production qualification
is always false. An ABI number, a BTF member list, a supplied boolean or useful
frames cannot certify a loaded object or runtime lifetime mechanism.

## B: Correct the native binding location before its approval

Keep the independently reviewed
[selected-accounting contract](2026-10-17-hotspot-selected-accounting-contract.md)
and its explicit operator gate. The current Gadget prepare call precedes collection
creation: prepare734, acquire890, assign899, publish maps991, start tracers1002,
attach programs1012 in the current prepared source.

Keep early profile/target parsing and owned options copying in prepare. Acquire
the actual selected-map receipt immediately after successful `i.collection =
collection`, before the OTel tail-call setup/map publication/reader startup/link
attachment. Failed binding returns its original error joined with checked cleanup
of the acquired, not-yet-published collection. The getter publishes only a real
acquisition; final owner sampling and all old negative/live proof gates remain.

This planning correction changes no native source or pin. Native implementation
still requires separate explicit operator approval. Existing Plan04 Task1 is
complete; do not repeat its host implementation or imply Task2/3 are complete.

## C: Profile actual public consumption

The completed dense evidence has a rejected public readout with225/600marked pairs;
a separate real raw stream has all640event/terminal pairs (40warmups+600marked).
That does not prove where public consumption stalled. Keep the line-copy change
deferred until actual materiality is established.

Add a test-only opt-in JFR mode to the existing owned public fixture. Preserve its
target, source graph, marker count/pace, lifecycle, refusal and retirement checks.
Record actual stdout decode/map/consume, synchronized interpreter/machine work,
summary completion, diagnostic rendering and thread blocking. Retain accepted and
rejected artifacts, acquisition/build receipts and actual JFR runtime/settings.
Run three alternating control/JFR pairs as diagnosis, not as an optimization
benchmark: JFR itself adds work and openat events. No output is promoted merely
because the success-or-rejection diagnostic is green.

This work ends with a concrete next-bottleneck decision, not an invented fix.
Production parser/queue/lock/lifecycle changes require a follow-up proposal with a
measured cause, a meaningful regression and unchanged validation/ownership budgets.

## D: Preserve version and evidence boundaries

Our pinned upstream uses perf trace delivery; inspected current upstream uses a
ring buffer and changed trace structures. Its error counters are telemetry, not a
session-bound final conservation certificate. The existing Gadget occurrence ring
and definition stream are separate sources. No upstream rebase is part of this
plan; the newer commit is comparison evidence only.

Record source input hashes **before** each new qualification build, command/cwd,
Go/JDK/toolchain versions, module replacements, source-selection settings and output
binary hashes. Use `-buildvcs=false` and explicit source receipts; installed Go's
linked-worktree VCS stamp is not an authority. Preserve the earlier
INFERRED/UNVERIFIED historical linkage instead of rewriting old receipts.

## Completion and supersession

- Revised Plan05 replaces its unexecuted audit/BTF-only/duplicated-sidecar proposal.
- Plan04 is amended only at its early-validation/acquired-binding boundary; its
  native approval gate and remaining implementation/test tasks remain active.
- The new consumer plan supplements completed Plan02 measurements, not reruns of
  that completed task sequence or a revival of its deferred buffer change.
- Loom and native digest activation remain under their existing independent plans
  and approval requirements. Nothing here promotes a production runtime or policy.
