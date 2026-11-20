# HotSpot persisted-profile acceptance — partial checkpoint, 2026-10-24

**PARTIAL: reviewed fork/source/host gates, not persisted-profile acceptance.**
Task5 safe preparation is reviewed; its actual image/node/trust gate is **BLOCKED**.
Tasks6–7 are **NOT_RUN**, with null exits. Final Task8, whole-integration review
and release remain incomplete. This additive checkpoint supersedes only the
then-missing gates below in the [2026-10-23 handoff](2026-10-23-hotspot-runtime-qualification.md);
it does not rewrite historical failures or authorize runtime work.

## Separate lanes

| Lane | Actual outcome | Remaining boundary |
|---|---|---|
| Bounded public native | Attempt4 independently reviewed **PASS**; XML1 test/0 skips/0 failures/0 errors;400 marked/380 useful/176 compiled-marked | Standalone collector exit **null**; no broader A3, strict or persisted-profile acceptance |
| Storage publication | Exact reviewed33-path package published to the selected personal fork; source/readback reviews clean | **Fork-backed**, not canonical upstream acceptance/release |
| Resolved host consumers | Actual remote fork version/sums/Origin verified; three builds, two race commands and vet exit0; review clean | Host binaries are not images or live API/runtime evidence |
| Opt-in capability source | Only SYSLOG added; exact six-capability behavioral RED then seven-capability GREEN, connected races/vet/build exit0; review clean | Requested seven capabilities are **not effective post-exec authority**; issue248 stays open |
| Images/node/trust (Task5) | Full-byte9-payload/6-OCI-asset verification and exact source contexts prepared/reviewed | **BLOCKED**: matching images, dedicated direct-node provenance/UID1000 authority and original trust inputs absent |
| Current server API (Task6) | **NOT_RUN**, exit **null** | Source-matched server and original JSON/protobuf Create/Get/false-key/UID/Delete evidence |
| Persisted live (Task7) | Positive/recreation/controls **NOT_RUN**, exits **null** | Task5 inputs, effective authority and separate case grants; no new native grant |
| Quality | This checkpoint's offline `:profiler:detektTest` actually **FAILED**, exit1;16 errors and520 warnings | Separate issue528 maintenance; previous configured build/native fixture does not waive lint or N CI/runtime gates |
| Canonical release / historical obligations | Canonical acceptance **pending**; strict-eight **FAILED/INCOMPLETE**, dense600 and unknown-owner limits unchanged | Final integration/release review, broader qualification and historical custody/retirement obligations remain open |

## Selected fork and host evidence

After [upstream discussion455](https://github.com/kubescape/storage/issues/455),
the operator selected the temporary storage fork. Actual publication is
[Pilleo/storage commit6815ab7d489114310ecb8e22b45909f8c86e944f](https://github.com/Pilleo/storage/commit/6815ab7d489114310ecb8e22b45909f8c86e944f),
branch `contrib/hotspot-typed-stacks-ignorerule`, tree
`c4c37990c37ae39270ef14ca7d2e00f3ad33334d`, sole frozen parent
`d963c7941f5bd493751109324e6eba41f7d2b5af`. The exact33-path package excludes
mixed history, build and private artifacts. SSH publication exited0 in3.170s,
with full ref/commit/tree/parent/all33-source readback. Canonical main was untouched;
earlier failed HTTPS/REST/display/cleanup telemetry remains failed evidence.

The unchanged canonical require `github.com/kubescape/storage v0.0.303` is replaced
explicitly by remote `github.com/Pilleo/storage v0.0.0-20261024002608-6815ab7d4891`:

- Origin.Hash: `6815ab7d489114310ecb8e22b45909f8c86e944f`.
- Sum: `h1:ABEuHtiiTsvP5laJU5PiMdMhKR27KSJ0MZISRoVPcJM=`.
- GoModSum: `h1:d/1hqWPda2clsjx2wmQgysnB5dThIo3rDKP7RWx+v+M=`.

All33 source hashes match; both graphs contain1136 modules with only the selected
replacement changed. No local storage/workspace substitute or unrelated upgrade.
Node/run/verify builds, examples and connected-consumer races, vet and diff checks
all reached exit0. Task4 then selected the corrected runner SHA256
`0b771183a0c27ec224f7a11b90c3df3c080c3d8b481f9e6138b3a80321ce2a9a`;
Task3 node/verifier reuse is supported by unchanged dependency closures.

SYSLOG is broader than symbol lookup. The opt-in source request preserves UID1000,
nonprivileged Pod, mounts, baseline sensors, archive, flags and fail-closed errors;
it proves neither effective credentials after exec nor BPF/target-memory access.
See [issue248](../backlog/issue-20261023-234809-248-carry-kernel-symbol-permission-into-the-opt-in-hot.md).

## Concrete environment and quality boundary

Prepared fork-server/node/workload contexts and a cached Temurin FROM pin are not
qualified images. No selected dedicated direct node, accessible UID1000 local
containerd socket, initial PID/procfs proof or original NodeOrigin/BuildReceipt
exists. The observed socket is root:root0660 and inaccessible; PID1 namespace reads
were denied. The discovered `microk8s` context name was neither selected nor contacted.
Missing inputs also include the normal compiler/runtime bases (`golang:1.27-trixie`,
`golang:1.25-trixie`, distroless `latest`), normal `tracers.tar`, qualified builder
caches and complete independent image/JVM provenance. Host caches and cached base
metadata cannot substitute. Next is explicit operator selection of an owned
direct-node environment or provisioning scope/budget, then a reviewed input batch;
there is no invented launch command, image digest, trust fixture or root/kind/remote
CRI fallback.

Exactly one `./gradlew --offline :profiler:detektTest` ran from MazeWall,
2026-10-24T02:56:22.885082Z–02:57:38.881906Z (75.997s), terminal exit1,
**not SKIPPED**. Its current XML reports11 ImplicitDefaultLocale,
3 SwallowedException,1 UnreachableCode and1 TooGenericExceptionThrown errors,
plus520 warnings. This is a fresh count, not reuse of the historical16.
Original stdout/stderr and generated reports are retained, with preexisting
derivative lint reports copied before execution. No fix, suppression, fixture
cleanup, rule/config change or opt-in enablement occurred.
[Issue528](../backlog/issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md)
owns separate maintenance; [issue752](../backlog/issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md)
retains timing/real endpoint-retirement obligations. Both remain unchanged/open.
The prior configured228-task M build remains applicable to unchanged executable
inputs, with its warnings/skips/native opt-ins disabled; it does not cancel this
lint failure, N privileged CI/gadget prerequisites or current runtime gates.

## Evidence custody and unchanged limits

The [independent native disposition](2026-10-23-hotspot-public-native-attempt.md#independent-original-evidence-disposition--2026-10-24)
accepts only the bounded original fixture. Global collection completeness/coverage
and policy eligibility remain **false**; original standalone native exit is null,
all four native grants stay consumed, and strict/dense/unknown-owner history is not
reclassified. Production remains pinned Temurin25.0.4.1+1-LTS,
`-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry`.

Operator-only, **nonpublic code references**, not published release proof:
`P = .superpowers/sdd/2026-10-24-hotspot-persisted-profile-acceptance`;
`P/native-outcome-review.md`, `P/task-2-ssh-publication-report.md`,
`P/task-2-fork-outcome-review.md`, `P/task-{3,4,5}-{report,review}.md` and
`P/environment-scope.md` retain the selected original reports/reviews.
This partial checkpoint's exact commands, findings, recoverable baselines,
task-only diffs and preservation checks are in `P/private/task8-partial-1/`, with
`P/task-8-partial-report.md`. Independent controller review of this new doc/quality
delta remains required; no final Task8/integration/release acceptance is claimed.
