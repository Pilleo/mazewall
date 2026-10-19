# Memory-bounded mutation CI

**Status:** Approved for subagent implementation by the operator on20October2026; no merge authorization.
**Base:** `38943a8c2c362d234bc4391da7513a1135638683` (PR #545).
**Scope:** First follow-up: bounded mutation settings and isolated-runner scheduling.
Build-tool lifecycle decoupling, additional cache archives and kernel sharding are
separate follow-ups, not hidden requirements of this change.

## Intent and evidence

Reduce the main CI critical path without repeating the operator's previous memory
shortage. Current measured validation time is15m27s: kernel stage5m58s, mutation
stage2m52s, optional acceptance10m26s. The host gate restores134/171 tasks from
cache; the container archive restores in27s. These are observations, not targets.

A concrete concurrency mismatch must be fixed before fan-out:
`.github/workflows/ci.yml` passes `-Pmazewall.pitest.threads=2`, but enforcer and
platform read `System.getProperty("pitest.threads")` and default to4; profiler
uses the project property. `org.gradle.parallel=true` and two task workers can
also overlap separate module mutation tasks.

## Requirements

- Use JDK 25 and `ubuntu-26.04`; preserve JDK 22+ library compatibility.
- Add no dependencies, dependency upgrades, native/runtime changes or worktrees.
- Parallelize across separate GitHub-hosted runners, never by backgrounding heavy commands in one checkout.
- Mutation CI uses `--max-workers=1 --no-parallel`, two PIT workers and one ordinary test fork; run the three module PIT tasks serially.
- Resolve PIT threads consistently: `-Pmazewall.pitest.threads`, then compatible `-Dpitest.threads`, then default `2`; reject non-integers and values below `1`.
- Bound the PIT parent to `-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m` and each minion to `-Xms128m -Xmx256m -XX:MaxMetaspaceSize=256m`; preserve `--enable-native-access=ALL-UNNAMED`.
- Preserve existing Gradle heap/metaspace settings, existing kernel forks, all mutation targets/exclusions/timeouts/thresholds, real assertions and fresh native execution.
- Require at least `2147483648` bytes of observed memory headroom and zero additional OOM kills on the new mutation runner; unknown memory evidence is not a qualified pass.
- Preserve both existing required check names, fail-closed publication, serial operator/orchestrator merges and PR-only cancellation.
- Preserve host/container cache separation, ownership checks, SHA/source identities and native-result non-caching; add no new container archive.
- Never modify, filter, or handle `GITHUB_TOKEN`; preserve existing credential bindings.
- Retain full local `./gradlew build` verification. Do not remove the buildSrc JAR's regression dependency in this change.

Heap sums alone are not a memory proof: native allocations, metaspace, compiler
and idle TestKit daemons, page cache and OS processes also consume memory. The
heap limits above are proposed conservative caps. If real mutation checks cannot
pass them, stop for review; do not automatically enlarge heaps or weaken tests.
This design reduces risk and requires measured evidence; it cannot guarantee
that every future workload or runner image is immune to OOM.

## Job graph

Rename the current implementation job ID to `core-quality`, retaining its commands
except mutation execution. Its display name becomes `Core Quality and Kernel
Validation`. Add independent `mutation-tests`, displayed as `Mutation Validation`,
on its own runner. Optional acceptance remains independent and unchanged.

Retain job ID `build-test-publish` and display name `Build, Test, Security Scan &
Publish` as a small, always-evaluated success join of `core-quality` and
`mutation-tests`. Explicitly require both results to equal `success`; failure,
cancellation, a skipped prerequisite or a missing result must fail the join.
Forward `container_cache_hash` from `core-quality` through this join.

Publication keeps `needs: [optional-integrations, build-test-publish]`, its normal
success condition, default-branch-push condition, existing cache-output reference,
command and credential bindings. Thus existing required check names still cover
mutation failure without relying on an out-of-band branch-protection edit.

The mutation job uses host Gradle: these are already host-only unit mutations.
It needs checkout, JDK25, setup-gradle with PR-scoped writes and the existing
code-path filter, not Podman or the native runner image. Run the same three tasks
with the bounded settings above. Add CI workflow changes to the code filter in
both lanes so a workflow-only budget/scheduling change actually exercises mutation
validation. Docs-only changes may skip the mutation step, as today, but the job
itself still reports success. No job-level skip or continue-on-error shortcut.

Separate runners mean separate RAM, caches and workspaces. A runner-concurrency
quota may queue a lane; do not collapse lanes into the same machine to avoid it.

## Memory evidence

One small standard-library Python command, `scripts/ci_resource_probe.py`, wraps
the mutation Gradle command and writes a JSON receipt under
`build/reports/ci-resources/`. Sample once per second; include initial/final and
minimum observed headroom, host memory, visible cgroup-v2 memory limits/current/
peak counters, OOM-kill counter deltas, elapsed time and command exit status.
Resolve the process's actual cgroup and visible ancestors, not a guessed root.
Effective headroom is the minimum of host `MemAvailable` and every visible finite
cgroup's `memory.max - memory.current`. Account for cgroup peak usage when present;
a pre-existing/unattributable peak must be reported rather than reset silently.

Refuse to launch when initial headroom is below the reserve. Record missing or
malformed counters as unknown; never label them zero OOMs. Preserve a failing
command's exact exit status; successful commands with low headroom, increased OOM
counters or unqualified evidence return nonzero. Preserve stdout/stderr and
interrupt/cancellation semantics; stop the sampler and retire only owned children
on interruption. Do not stop unrelated Gradle daemons, alter swap or poll credentials.

Upload receipts and mutation reports with `if: always()`. One-second sampling can
miss short pressure spikes: retain cgroup peaks/OOM events, and do not describe
sampled headroom as a hard memory reservation. No automatic retry after OOM.

## Rollout and acceptance

1. Normalize/bound settings and test the real resolved PIT configuration.
2. Exercise the mutation command with memory receipts before accepting job fan-out.
3. Move it to the isolated lane and verify the required-check join/publication graph.
4. Require green complete local verification, one independent whole-change review,
   and hosted cold/unchanged-head warm results, including memory receipts.

Compare wall time and total runner minutes against run39327392885. Report queue,
setup/cache transfer, build and execution time separately. No promised six-minute
result: optional acceptance is still a measured10m26s lane, and the bounded PIT
settings may execute more slowly than the previously accidental four-worker mode.

Writing or approving this document does not authorize merging PR #545 or this
follow-up, updating branch protection, or changing the existing merge process.
