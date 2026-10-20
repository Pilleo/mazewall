# Admission digest qualification and activation evidence — 2026-10-17

Decision: **not_authorized**. Java remains default. The
[proposed contract](2026-10-17-hotspot-native-digest-contract.md) requires approval
before native/public implementation or activation. Host qualification is not
equivalent OCI admission or an in-process deadline/retirement guarantee.

## Reused revisions and evidence

Task 1 `a4d79532dceb7195e9bb275fd359245c3c45922d` introduces the private Java
digest boundary. Task 2 `1d34405bf498c36ad7865919d29a0feb67921c86` adds only
experimental Go qualification. Task 3 is documentation-only on that base;
measurements are reused, not rerun or relabeled as Task 3 execution.

Retained local handoff evidence (checkout-relative links, not packaged/committed
artifact payloads):

- [Task 1 report](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/task-1-report.md),
  [focused tests/ABI/lint](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-1/green-abi-lint-final.log),
  [full hook](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-1/precommit-test.log).
- [Task 2 corrected report](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/task-2-report.md),
  [native known answers/race](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/green-race-final.log),
  [measurement script](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/measure.sh),
  [actual invocations](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/measurement.log),
  [full hook](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/mazewall-precommit-test.log).
- [Provenance correction](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/provenance-resolution.md),
  [follow-up index](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/provenance-followup/followup.sha256).
  The original index still refers to preamendment report bytes; do not rewrite it.

Task 1 focused assets/backend suites: 18 tests, 15 passed, 3 environment-gated
skips, zero failures/errors. Kotlin/legacy ABI and lint passed; final hook passed
in 9m2s (242 tasks: 77 executed, 165 up-to-date). Task 2 changed no JVM source;
its full hook passed in 4m22s (242 tasks: 31 executed, 211 up-to-date).
Skipped/incremental tasks are not fresh test execution proof. Task 3 reuses valid
source checks/native known answers; its scoped commit's unmodified full-build
hook supplies the completion gate, with outcome recorded in the local Task 3 report.

## Host support and upstream policy are separate facts

Observed Linux 7.2.10-x64v3-xanmod1 amd64, Intel Core i5-1335U, UID/GID1000;
Go1.26.8, module Go1.26.0, x/sys v0.47.0. Matching config: CRYPTO_USER_API/HASH=m,
SHA256=y; af_alg/algif_hash present; actual SHA256 operations succeed.
/proc/sys/crypto and af_alg_restrict absent (ENOENT), **not a value of zero**.
/proc/self/attr/current returned EINVAL; no SELinux permission inference. See
[host support](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/host-support.txt).

Separately, official current Linux docs retrieved as **7.3.0-rc7 on 2026-10-17**
call AF_ALG insecure/deprecated and recommend userspace crypto:
[userspace crypto](https://docs.kernel.org/crypto/userspace-if.html),
[crypto sysctl](https://docs.kernel.org/admin-guide/sysctl/crypto.html).
They describe 7.3 restriction 0 unrestricted, 1 allowlist (default), 2 disabled;
disallowed bind ENOENT, disabled socket EAFNOSUPPORT, possible SELinux restrictions.
These are not this 7.2 host's defaults. No policy changed; old85ms is insufficient
to authorize activation or accept a deprecated provider.

## Actual measurements and semantic limits

Same canonical graph: 9 files, **68,487,216 bytes**; manifest SHA256
`0584b244540dbc615a126b690144b68eb774963f6c5c969a252445abb5669b04`;
collector SHA256 `afb3fd5a0a994a06ed70e42689abe586b926b21fe2967db2c423c73a60c3907e`.
All listed CLI digests agree; independent rehashes and existing Go verifier pass
before/after. No regenerated candidate or cache; common Java verification remains.

Three alternating fresh-process pairs, no warmup, uncontrolled scheduling/cache:

|Pair/order|Java full asset-mode ns|Java checksum ns|Go native bundle hash-only ns|Java outer wall ns|Native outer wall ns|
|---|---:|---:|---:|---:|---:|
|0 Java/native|27,174,061,380|27,057,814,090|56,347,650|27,886,801,802|70,389,751|
|1 native/Java|32,132,355,694|31,930,843,168|67,419,168|32,887,865,759|82,126,265|
|2 Java/native|19,746,061,215|19,633,217,072|57,154,875|20,426,009,081|71,449,714|

Java is current HotSpotPerformanceMain assets, pinned Temurin25.0.4.1+1-LTS,
-Xint -XX:+UseSerialGC, installed digest-pinned container, no pull. Its timer
includes common admission/wrapper. Native is a compiled non-race benchmark;
manifest parsing is outside its timer. It hashes/checks every asset and close but
does **not** perform common ownership/provenance/OCI admission. Native processes
exit0 under external15s/kill-after2s bounds, Java exit0 under120s/kill-after5s with
actual container absence checked; none timed out.

Outer baselines: Java/container -version 755,224,271ns; native test harness without
benchmark 16,352,400ns; separate CLI usage 6,964,093ns (expected exit2, no AF_ALG).
They include orchestration and are not subtracted. Nine fresh CLI hashes took
0.07s with shell/comparisons, neither benchmark timing nor full admission.
These numbers do not prove a production admission speedup or native deadline.

## Known answers, errors and cleanup

Explicit go test -race -tags mw_kernel ./internal/admissionprobe -count=1 -v passed
sizes **0, 3, 55, 56, 63, 64, 65, 65535, 65536, 65537, 1048576**, exact budgets,
nonempty size-minus-one rejection, source unchanged. Invalid budgets -1/536870913
reject before open; missing-file ENOENT preserved. Native tests are explicitly
tagged and fail rather than skip on denial. Prepared ordinary host suite passed;
raw unprepared go test ./... failure remains recorded, not counted as passing.

[Success trace](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/native-success.strace)
shows real socket4, null-address accept4 acquiring5, source6, MSG_MORE updates,
empty final send, exact32byte receive, successful close6/5/4 and thread exits0.
[Original failure trace](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/native-failure.strace)
retains the address-requesting wrapper's ECONNABORTED after successful socket/bind,
algorithm close and exit1. Pinned x/sys source documents the null-address AF_ALG
requirement; raw SYS_ACCEPT4 corrected that calling convention, not permission.
[CLI error records](../../.superpowers/sdd/2026-10-17-hotspot-admission-provider-next/artifacts/task-2/cli-error-cases.log)
cover usage/parse, invalid/insufficient budgets, missing file and EISDIR read,
with acquired-resource cleanup traces and no false digest output.

Independent close failures, short-I/O and EPERM/EACCES injection are **not proved**;
handling was reviewed in Go source only. No forced stalled syscall, JVM native
worker/arena lifetime, timeout race, public pending-owner retry or eventual stalled
worker retirement is demonstrated. External timing and normal process termination
do not establish in-process deadline or physical-retirement guarantees.

## Historical source provenance correction — activation concern

**Measured binary identities verified; exact historical source-byte linkage
INFERRED/UNVERIFIED**, not exact source provenance. Retained binaries still match
the original premeasurement hashes:

|Binary|SHA256|
|---|---|
|CLI|fc875743680b1c5a6d97b4ea57399c5f74839dedb25c6e183cbf0523b5874048|
|Benchmark test|d8d7b5cc0409664e1d4409013467d8202f6eac7cd0505fe8a245b795656d4c66|
|Verifier|b7ff5d218d2c56ae29b9628cad6b30327cea6cb8a30200f86481602b01f8f592|

Installed Go1.26.8 cmd/go/internal/vcs/vcs.go requires .git to be a directory;
it ignores this linked worktree's 92-byte .git pointer, walks to the ancestor
source checkout and runs Git status/log there. That checkout's
3099e2b0afa9a4c8168e52dd07d92c27b51fcdda matches the CLI stamp. Later go build
-n -x plus strace reproduces ancestor Git cwd without compiling/linking. GOMOD
and embedded objdump paths identify the intended source location, not source bytes.
The provenance correction records the actual successful final build request,
distinct from an earlier set-e request that stopped before builds. No contemporaneous
hashes of the three new Go files or compiler-input receipt were recorded. Current
source SHA/cmp matches commit1d34405b; later verification cannot bind old inputs.
No new build or measurement repairs that historical gap; none was needed here.

The [source-receipt follow-up](../internals/backlog/issue-20261017-173125-908-record-qualification-source-receipts-and-linked-wo.md)
requires future contemporaneous source/build/binary receipts. Canonical
profiler/hotspot-agentless/Makefile already uses -trimpath -buildvcs=false, which
intentionally suppresses VCS metadata, unlike default experimental probe builds;
it is not itself a source receipt. This is an experimental qualification evidence
gap, not a production vulnerability or a task to fix upstream Go. Carry this limit
into every activation review.

## Completion boundary

The [performance issue](../internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md)
remains open: complete native equivalent admission timing, in-process deadline/
retirement and successful dense600/1ms public readout are not established. Approval
needs actual missing tests, preserved ABI, future contemporaneous qualification
receipts and explicit operator acceptance of deprecation/host restrictions.
Decision remains **not_authorized**.
