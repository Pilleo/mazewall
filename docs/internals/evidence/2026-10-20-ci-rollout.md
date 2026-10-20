# CI rollout observations — 2026-10-20

## Merged security repair: actual default cold run

[Run 39483882935, attempt 1](https://github.com/Pilleo/mazewall/actions/runs/39483882935/attempts/1)
ran the approved default push at `512565a7bc08aae0466687fe5adcd2e7f76009e0`.
All workflow jobs succeeded; this was not a controller-triggered rerun.

| Observation | Actual evidence |
|---|---|
| Security job | `118839185252`: compatible data restore missed; update completed in 20m29s |
| Completed snapshot | Cache `9237869445`, ref `refs/heads/master`, key `dc-data-v1-Linux-X64-dc10.0.4_39483882935-1`, 115,573,466 bytes |
| Persistence | Restored/updated/final caller-ownership checks passed; explicit completed save, exact lookup and literal-hit assertion passed |
| Analysis | Actual Dependency-Check 10.0.4 analysis executed with unchanged CVSS7 policy; report Last Checked `2026-10-20T19:49:58Z`, Last Modified `19:47:05Z` |
| Analysis scope | Report contains two root dependencies (`annotations-13.0.jar`, `kotlin-stdlib-2.4.20-Beta1.jar`), zero reported vulnerabilities; this is not proof of full multi-module production coverage |
| Native/core/demo | Owning jobs and actual kernel/fresh-JVM tasks passed; demo reported all required comparisons matched. Profiler rootful suite recorded 19/19 skipped; not every native scenario ran |
| Optional acceptance | Real producer/Koog/ordinary-JAR/finished-Boot/native acceptance passed; fixture publication version `0.1.0-integration.512565a7bc08` is not the registry SNAPSHOT identity |
| Mutation | All three PIT tasks actually executed, not FROM-CACHE; unchanged scores/floors passed |
| Mutation memory | Receipt `pass`, command exit0, minimum headroom 9,308,479,488 bytes, additional OOM kills0; this does not establish other lanes' memory headroom |
| Publication | Job `118853096939` executed ten concrete upload tasks successfully: nine module publications plus the Gradle plugin marker; immutable remote payload identity is not independently established |

The nine module uploads cover platform, enforcer, profiler, portal runtime/worker/codegen,
Koog, Spring Boot and the Spring Boot starter; a separate codegen plugin-marker
upload also ran. The root publication aggregate was UP-TO-DATE and is not a root
artifact upload. Successful upload tasks and existing
registry package records do not prove a separately downloaded binary matches this
commit; mutable SNAPSHOT package timestamps are not immutable payload provenance.

## Observed time, not a speedup claim

Workflow wall time was **38m49s**, with **93.72 summed runner-minutes** (not a billing
statement). Optional acceptance was the longest validation job; publication then
added work after its prerequisites. Cold scanner-data download is distinguished
from ordinary Gradle cache warmth.

| Job | Duration |
|---|---:|
| Core Quality and Kernel Validation | 21m31s |
| Mutation Validation | 12m17s |
| Koog and Spring Integration Acceptance | 28m31s |
| Security Validation | 21m09s |
| Required join | 3s |
| Publish Verified Packages | 10m12s |

Exact artifact IDs: `12294306176` core diagnostics, `12293830760` mutation,
`12293443632` optional acceptance, `12293438826` security. Evidence was fetched by
numeric ID and safely extracted, not selected by an ambiguous name across attempts.
Raw API responses/logs/reports/digests and the command ledger are external under
`/tmp/opencode/ci-rollout-20261020/task-1/`; they are not committed bulk artifacts.
An evidence review corrected the first report's count of nine uploads by locating
the earlier plugin-marker task at publication log line4245; module tasks follow
at lines4288–4415. This accounting correction does not change the raw run outcome.

## Still open

- Default warm restoration and a later normally due update/reanalysis have not
  been observed. PR warm snapshots do not supply this default-ref evidence.
- A targeted default security rerun can republish the same SNAPSHOT coordinates.
  Same-version publication is not an idempotent no-op; do not trigger it solely
  for benchmarking without normal release approval. Prefer the next independently
  approved default push for warm observation.
- Full dependency-scan coverage, skipped rootful scenarios, non-mutation memory
  receipts, actual root-build execution and immutable registry payload identity
  are not established by this run. No broader security/resource guarantee is made.
- The Portal retirement cause remains unresolved. Passing tests are not a fix.
- The explicit build-logic optimization is not part of this baseline. Local
  development may proceed after evidence review; its rollout needs its own actual
  suite/memory/native/review/CI evidence and must retain every security gate.

## Candidate local build-logic evidence — not hosted rollout

Source tasks `ad8addf1` and `3571f7a5` passed scoped review and focused regressions,
including direct ordering edges/negative controls, current workflow-input failure
and all625 four-result join tuples. Baseline job-map checks retain the security and
publisher blocks unchanged. Neither deleted folder was recreated.

The actual resource-wrapped strict-cache gate executed its complete nested suite
in259.34s, receipt `pass`, minimum observed local headroom11,688,038,400bytes and
additional OOM kills0. The unchanged14.78s repeat reused strict configuration cache
and left the nested verifier UP-TO-DATE: reuse evidence, not a second full workload.
Raw receipts/logs are external `real-gate-first.{json,log}` and
`real-gate-reuse.{json,log}` under `/tmp/opencode/ci-rollout-20261020/`.

Focused script ktlint passed. Complete local build including Koog bootstrap and
Spring passed19m13s,282 tasks (188 executed/4 cached/90 up-to-date). These observations
qualify the actual candidate local workload only; they do not fill missing baseline
lane memory, actual root execution, all-dependency coverage or hosted cache/native/
publication evidence. No numerical optimization benefit is claimed yet.

Matched local warm help/unitCheck measurements used the same host, argv, wrapper
and three warmed repetitions at512565a7 versus0c0608df. Medians: help8.359s/8.343s,
unitCheck17.583s/17.378s; ratio0.9884 passes the10% regression guard. All warm runs
reused strict cache. This is essentially unchanged steady performance, not a
numerical hosted speedup. Prime logs distinguish actual baseline implicit-suite
execution from candidate help isolation, but their cache states differ. The
feature branch was restored cleanly; no publication or deleted-folder restoration
was used to obtain these observations.
