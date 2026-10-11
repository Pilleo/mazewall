---
document_type: execution_plan
base_revision: d0ce1d9f03131906cd3f8cb42a9b066c1c8e1200
---

# Integration Foundation Finish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. The operator selected subagents on 10 October 2026 and explicitly rejected arbitrary change ceilings. Steps use checkbox (`- [ ]`) syntax for tracking; each justified change receives scoped review.

**Goal:** Finish the existing integration candidate with evidence-justified repairs, exact-source acceptance evidence, and an honest list of release prerequisites.

**Architecture:** Reconstruct the preserved, pre-extension test-quality repairs in one clean worktree and add the already-proven prctl fixture correction. Freeze each tested candidate, review coherent changes, integrate only accepted source, then test the exact integrated revision. Shared foundation, Koog/Portal, and Spring/JDBC retain independent acceptance statuses.

**Tech Stack:** Gradle 9.6.1; producer Kotlin 2.4.20-Beta1; stable consumer Kotlin 2.4.20; JDK 22 core/JDK 25 integrations; coroutines 1.10.2; producer serialization 1.9.0; Koog 1.3.0; Spring Boot 4.1.1; existing Linux Seccomp/Landlock, Gradle, and consumer harnesses.

**Spec:** `integrations/2026-10-06-mazewall-integration-foundation-plan.md`, `integrations/2026-10-06-mazewall-integration-foundation-design.md`, and the measured state in `.superpowers/sdd/2026-10-09-opencode-v1-zed-continuation/operator-pause-20261010.md`. Read the spec and this plan together. This is a recovery/acceptance subproject of the implemented foundation, not a replacement for its C0–C9/P0–P5/S0–S3/R0–R1 contracts.

## Global Constraints

The following requirements are copied from the consolidated design, section 2:

- Keep all work in the MazeWall monorepo as ordinary Gradle subprojects, not Git submodules.
- Preserve JDK 22+ core compatibility unless a separately reviewed compatibility change proves necessary; use JDK 25 as the initial integration test runtime.
- Initial Spring consumer target: Spring Boot 4.1.1 BOM on JDK 25, a planning choice within the currently documented supported Java range. This is not a tested MazeWall compatibility claim. Record exact resolved Kotlin, coroutines, Koog, Gradle, and JDK distributions in the baseline task; do not opportunistically upgrade the root compiler or vulnerable demo. [S8]
- First Koog release: selected first-party, bounded data-in/data-out JVM tools through the existing process Portal. No credential broker, staged workspace, general shell/MCP runtime, or arbitrary hostile/generated code in that release.
- Prefer upstream Koog dependencies. Fork only after a minimal reproducible missing extension point has been identified.
- Spring, Spring Security, Reactor, and Koog dependencies stay outside core enforcement and Portal implementation modules.
- Ordinary enforcement uses reviewed static kernel policies. Runtime stack authorization and USER_NOTIF approval are not prerequisites.
- Reuse dedicated JDK-backed executor factories, policy compilation, assessments, installation receipts, profiling coverage, and diagnostic evidence. Do not build a custom ExecutorService or a universal scheduler.
- Required protection failure prevents the affected invocation from starting. Optional hardening is separately labeled; explicit unprotected development execution never reports protected status.
- Never install restrictions from a virtual thread or incidentally on shared, direct, event-loop, or caller-runs execution resources.
- Process-wide installation is an explicit application bootstrap action. It is not a starter side effect. Scope applicability is not a guarantee of runtime safety.
- Installed kernel restrictions are not reversible call scopes. Closing an invocation, runtime, or receipt never claims to remove them from a living worker.
- A stricter policy update uses new resources or rejects the transition; the first release has no hot policy reload. A future reload must replace a domain generation, not mutate permissions in place.
- In-process worker restrictions are not heap isolation, tenant isolation, arbitrary hostile-code isolation, hard per-invocation CPU/RAM isolation, or guaranteed termination.
- No automatic retry after a timeout, transport error, denial, or ambiguous external effect.

The implementation spec additionally fixes: “Baseline: Gradle 9.6.1, root Kotlin 2.4.20-Beta1, coroutines 1.10.2, serialization 1.9.0, JDK22 core floor, JDK25 integration runtime, Spring Boot4.1.1 BOM and `ai.koog:agents-tools:1.3.0`.” Stable consumers use **2.4.20**, without metadata/prerelease skip flags. The Koog consumer's host serialization **1.10.0** remains separate from the worker's **1.9.0** closure.

Current operating constraints:

- Preserve the linear BPF compiler, JVM coordination syscall floor, exact native layouts, immediate errno capture, actual FD/process ownership, and Landlock-before-Seccomp order. Never combine TSYNC with NEW_LISTENER.
- Never inspect, modify, or filter `GITHUB_TOKEN`; authentication and remote release actions belong to the operator.
- Use existing dependencies and architecture. A concrete failure or required spec contract can justify additional tests, callers, production fixes, and corresponding documentation. Record the reason, affected interfaces, and necessary verification before expanding the declared task scope. Repository approval requirements for new dependencies, unrelated breaking APIs, and BPF design changes still apply.
- No assertion/profile/budget/skip/coverage/Detekt-inventory waiver. An unsupported required test is a prerequisite, not a pass.
- Preserve all dirty/untracked operator work and retired worktrees. Stage only explicit owned paths/hunks. No reset, clean, whole-tree staging, or unrequested stash.
- One modifying owner and one shared verification lane. Subagent execution is authorized; no nested delegation or revival of retired AO sessions.
- Git commits use `Leanid Piliptsevich <pilleo19@gmail.com>` and per-command `core.hooksPath=scripts/hooks`. Keep the mandatory hook unchanged.

---

## Starting Candidate and Evidence-Based Scope

**Selected starting input:** the last recoverable pre-extension quality snapshot plus the prctl correction. It currently comprises **25 source files**: 15 original enforcer quality files, one internal process-handle getter, five runtime quality files, three profiler quality files, and one prctl fixture file. This is an inventory of preserved work, not a file-count limit. Additional changes are included when a concrete failure, review finding, or required contract justifies them; record that justification and extend `source-paths.txt` before making the change.

**Unverified input:** the newest Broker proof extension in maze60, SHA256 `95ebfc2904305635d80f11f41b46df70a3fec10294702d1da114e23066993c0e`. Preserve it in place and initially recover the previous tested Broker SHA256 `5b81b6f8ec50a5c88f22f4e042bc8a0b31070fef151ba301b585bd0630d8ce11` from the pinned pre-extension patch. If contract analysis identifies a genuine missing proof or defect, include the necessary extension after focused verification and review; its current lack of evidence is not a permanent exclusion.

**Why retain more than the one prctl file?** Its required quality gate actually exposed 45 existing enforcer errors. All-module checks subsequently exposed ten runtime and seven profiler errors. The preserved fixes are usable inputs, but e90 alone was rejected for changed-test semantics; retain the associated corrections rather than reintroducing swallowed failures or invented ownership.

**Stopping rules:**

1. Freeze before verification. No source edits while a check/reviewer owns that snapshot.
2. Continue justified correction work without a file-count or one-pass ceiling. Record each concrete finding, affected paths, source freeze, and covering tests; fix the cause and re-review the changed range. Do not repeat the same failed approach without new evidence. Ownership ambiguity blocks mutation until reconciled.
3. Additional Broker controls must address an actual contract/proof gap. No warning-inventory normalization, helper renaming to evade analysis, or speculative test matrices.
4. Checkpoint source/review progress at **30 minutes** and hand off a retired context safely; this is an operating checkpoint, not a change ceiling. Long checks retain their **1200-second** limit and continue independently of a model turn. Checkpoint before 45k request-context tokens; do not resume a retired/oversized context.
5. No unchanged replay after failure/skip. Retry only the affected check after an actual correction or diagnosed prerequisite change. Passed gates remain valid for unchanged tested inputs.
6. If a required original contract has no implementation/evidence, record it and assign a focused corrective task with its own test/review cycle. Continue while a grounded path exists. Stop only for an actual unavailable prerequisite, unresolved ownership, required operator authority, or a genuinely indeterminate design; never mark an unproved contract complete.

## File Responsibility Map

All source paths below are relative to the assigned worktree and describe the initial recovery. Extend this map and the source manifest when justified work reaches another file or interface.

| Selected file | Responsibility |
|---|---|
| `enforcer/src/integrationTest/kotlin/io/mazewall/DeploymentOriginNativeTest.kt` | Preserve native deployment evidence and fatal-first, all-attempted cleanup |
| `enforcer/src/integrationTest/kotlin/io/mazewall/PreparedOperationNativeTest.kt` | Existing prepared-operation quality repair; preserve accepted bytes |
| `enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ContainedExecutorsTest.kt` | Existing contained-executor quality repair; preserve accepted bytes |
| `enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ShutdownRaceConditionTest.kt` | Existing shutdown quality repair; preserve accepted bytes |
| `enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/VirtualThreadGuardrailTest.kt` | Existing virtual-thread guard quality repair; preserve accepted bytes |
| `enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/SupervisorOpenHelperIntegrationTest.kt` | Actual helper acquisition and rollback ownership |
| `enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/TraceeAuthorityIntegrationTest.kt` | Actual tracee identity, FD acquisition, and helper retirement |
| `enforcer/src/integrationTest/kotlin/io/mazewall/landlock/LandlockTest.kt` | Primary-causal filesystem permission evidence and propagated cleanup failures |
| `enforcer/src/integrationTest/kotlin/io/mazewall/landlock/LandlockTsyncIntegrationTest.kt` | Same permission/cleanup contracts across thread-synchronized fixtures |
| `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/BpfHardeningTest.kt` | Actual mapping/owned disposable-JVM retirement, retaining strict policies |
| `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/DedicatedProcessBaselineTest.kt` | Actual wait interruption, sibling outcomes, and physical worker end |
| `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/MmapProtectionTest.kt` | Actual immediate syscall result/errno and acquired mapping cleanup |
| `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/ProcessContainmentTest.kt` | Qualified launch diagnostics; reject spoofed or cleanup-only denial evidence |
| `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/StackingIntegrationTest.kt` | Existing stacking quality repair; preserve accepted bytes |
| `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SupervisorProxyIntegrationTest.kt` | Primary-causal permission evidence and executor physical retirement |
| `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperProcess.kt` | Internal handle of the actual acquired process; no PID inference |
| `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PreparedPortalOperationIntegrationTest.kt` | Checked TempDir injection |
| `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt` | Consume existing Results and retain existing child/socket retirement assertions; pre-extension bytes only |
| `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerGenerationTest.kt` | Locale-independent generation fixture |
| `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/StableWorkerCacheGenerationTest.kt` | Checked TempDir injection |
| `portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/WorkerPhysicalReceiptLifetimeTest.kt` | Checked TempDir injection |
| `profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProfilerStressTest.kt` | Qualified task-failure propagation |
| `profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt` | Checked nullable diagnostics and preserved cleanup/fatal priority |
| `profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotSupportedWorkloadMain.kt` | Qualified real pending-owner retry cleanup |
| `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt` | HotSpot-compatible prctl fixture and exact unsafe-prctl denial controls |

Evidence/document paths:

- Track this existing plan, `docs/superpowers/plans/2026-10-10-integration-foundation-finish.md`, only in the later approved metadata commit. `/docs/superpowers/` is Git-ignored; preserve that operator rule and use the exact-file force-add shown in Task 9.
- Create ignored `.superpowers/sdd/2026-10-10-integration-foundation-finish/verification/`: immutable inputs, source manifest, command logs/process receipts/results, review, and producer revision. This plan owns its ledger/briefs/reports in the parent workspace; prior-plan inputs are read-only transfer sources, not writable progress ledgers.
- Create `docs/internals/reviews/2026-10-10-integration-final-acceptance.md`: final per-gate evidence/prerequisite table and spec traceability.
- Modify `docs/internals/reviews/integration-compatibility-manifest.md`: exact new producer/artifact/compiler/JDK records only.
- Create candidate-namespaced locks under `tests/consumers/locks/<computed-version>/kotlin-2.4.20/` and `tests/consumers/koog-portal/locks/<computed-version>/`; the version is computed in Task 5, never guessed.
- Modify owned rows/hunks in `docs/internals/backlog/README.md` and the four existing issues listed in Task 9, only when their own acceptance is established.
- Read existing consumer READMEs, `scripts/koog-build.sh`, `scripts/run_tests.sh`, coverage conventions, native designs, and nearest `AGENTS.md`; do not modify them to force acceptance.

## Execution Variables and Check Procedure

Initialize in each new shell. `TASK` remains the source worktree; enter `ACCEPT` for final verification without rebinding source ownership:

```bash
set -euo pipefail
ROOT=/home/leanid/Documents/code/java/jseccomp
STATE="$ROOT/.superpowers/sdd/2026-10-10-integration-foundation-finish"
LEGACY="$ROOT/.superpowers/sdd/2026-10-09-opencode-v1-zed-continuation"
LOCK="$ROOT/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock"
BASE=d0ce1d9f03131906cd3f8cb42a9b066c1c8e1200
QUALITY=e90b27c01a2697e4ace8f43369bbe4e955786b13
TASK="$ROOT/.worktrees/fleet-v1-forward-20261010"
ACCEPT="$ROOT/.worktrees/fleet-v1-final-acceptance"
EVIDENCE="$STATE/verification"
PRCTL=/home/leanid/.local/state/mazewall-v1-ao/data/worktrees/maze-scanner/maze-scanner-13/enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt
export ROOT STATE LEGACY LOCK BASE QUALITY TASK ACCEPT EVIDENCE PRCTL
export GRADLE_USER_HOME="$LEGACY/gradle-hook-home"
export JAVA_HOME=/home/leanid/.sdkman/candidates/java/25.4.4+1-graal
export PATH="$JAVA_HOME/bin:$PATH"
export MAZEWALL_CONTAINER_ENGINE=docker TIER_E_CONTAINER_ENGINE=docker
export MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
if test -f "$EVIDENCE/producer-revision"; then
    REVISION=$(cat "$EVIDENCE/producer-revision")
    VERSION="0.1.0-candidate.${REVISION:0:8}"
    REPOSITORY="$ACCEPT/build/fleet-consumer-repository/${REVISION:0:8}"
    export REVISION VERSION REPOSITORY
fi
```

Set the working directory explicitly in every new shell: `TASK` for source checks, `ROOT` for integration/bookkeeping, and `ACCEPT` for final gates. The artifact-binding variables become available after Task 4 records the producer revision. Reinitialize this block and the `gate` function in a new shell; do not depend on a previous tool call's shell state.

Run long checks with this existing finite-command pattern, not a foreground tool that stops at 200 seconds. This shell function is session-local; it creates no repository tool or dispatcher:

```bash
gate() {
    label=$1
    shift
    test ! -e "$EVIDENCE/$label.process" || return 1
    test ! -e "$EVIDENCE/$label.log" || return 1
    export EVIDENCE
    nohup setsid bash -c '
        label=$1
        shift
        printf "%s\n" "$$" > "$EVIDENCE/$label.pid"
        ps -p "$$" -o pid,pgid,lstart,args > "$EVIDENCE/$label.process"
        cat "/proc/$$/stat" > "$EVIDENCE/$label.starttime"
        printf "%s\0" "$@" > "$EVIDENCE/$label.argv"
        timeout --signal=TERM --kill-after=10s 1200s "$@" > "$EVIDENCE/$label.log" 2>&1
        code=$?
        printf "%s\n" "$code" > "$EVIDENCE/$label.exit"
        exit "$code"
    ' bash "$label" "$@" > "$EVIDENCE/$label.supervisor.log" 2>&1 < /dev/null &
}
```

Each `gate` call is a separate step. Wait for its exit receipt and physical termination of its owned build/test/hook processes before another check or source edit. Inspect logs in bounded, at-most-60-second observations; a healthy finite check keeps running. Exit 124/137 is failure/timeout, never GREEN. Archive reports and source hashes with the result. Ordinary Gradle daemons are not proof of an active source writer; reconcile actual command/process ownership. Scripts with their own fleet lock are called without an outer `flock`.

### Task 1: Freeze the selected inputs and create the repair worktree

**Files:** Create the ignored evidence directory, `source-paths.txt`, `quality-base.patch`, `snapshot82.patch`, `prctl.snapshot`, `inputs.json`, and `candidate-source.json`. Read the exact source-file map above and original `coordinator4-quality-scope.json`. No root source edits.

**Interfaces:** Consumes root `BASE`, private `QUALITY`, pinned patch82, and pinned prctl source. Produces an isolated worktree at `BASE`, an initial source-path manifest, and immutable input copies. Later tasks consume those copies, not changing retired worktrees; evidence-justified expansions update the manifest.

- [ ] Inspect `git status --short`, `git diff --cached`, `git worktree list --porcelain`, live assignments, and owned processes. Require root HEAD `BASE`, empty staged diff, no active modifying owner, and free verification lane. Capture root status/tracked diff and existing untracked-path metadata in the evidence directory; preserve operator content, including six unrelated backlog rows. Load using-git-worktrees when executing; do not delete/reuse an occupied destination.
- [ ] Create the evidence directory and exact allowlist:

```bash
test ! -e "$EVIDENCE"
mkdir "$EVIDENCE"
python3 - <<'PY'
import json, os, pathlib
e = pathlib.Path(os.environ['EVIDENCE'])
s = pathlib.Path(os.environ['LEGACY'])
original = json.loads((s / 'coordinator4-quality-scope.json').read_text())['sourceFiles']
additional = [
    'enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperProcess.kt',
    'portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PreparedPortalOperationIntegrationTest.kt',
    'portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt',
    'portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerGenerationTest.kt',
    'portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/StableWorkerCacheGenerationTest.kt',
    'portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/WorkerPhysicalReceiptLifetimeTest.kt',
    'profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProfilerStressTest.kt',
    'profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt',
    'profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotSupportedWorkloadMain.kt',
    'enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt',
]
paths = sorted(original + additional)
assert len(paths) == len(set(paths))
with (e / 'source-paths.txt').open('x') as f:
    f.write('\n'.join(paths) + '\n')
PY
```

- [ ] Copy and verify the immutable inputs, then create the fresh branch/worktree:

```bash
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess
e = pathlib.Path(os.environ['EVIDENCE'])
s = pathlib.Path(os.environ['LEGACY'])
origins = {
    'snapshot82.patch': (s / 'coordinator82-owner59-WIP.patch', 'df53a785c0722400ecbe6d041739e1a493c9cd5738c82c68605e75a3c92d3f32'),
    'prctl.snapshot': (pathlib.Path(os.environ['PRCTL']), '2f701511d14d026805f5f068bf6ab6fbda0baa6def4e67342f3a37b2925116e7'),
}
for name, (source, expected) in origins.items():
    data = source.read_bytes()
    assert hashlib.sha256(data).hexdigest() == expected, source
    with (e / name).open('xb') as f:
        f.write(data)
patch = subprocess.check_output(['git', '-C', os.environ['ROOT'], 'diff', '--binary', os.environ['BASE'], os.environ['QUALITY']])
with (e / 'quality-base.patch').open('xb') as f:
    f.write(patch)
with (e / 'inputs.json').open('x') as f:
    json.dump({'base': os.environ['BASE'], 'quality': os.environ['QUALITY'], 'inputHashes': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [e/'quality-base.patch', e/'snapshot82.patch', e/'prctl.snapshot']}}, f, indent=2)
PY
git -C "$ROOT" worktree add -b fleet/v1/forward-20261010 "$TASK" "$BASE"
```

Expected: exact pins match; destination/branch creation refuses occupied names. Do not resume maze13/59/60 or copy their local configs.

- [ ] Apply only the selected existing repairs, preserving the newest Broker extension in its original tree:

```bash
cd "$ROOT"
./scripts/adkw blast-radius SocketHelperProcess
git -C "$TASK" apply --check "$EVIDENCE/quality-base.patch"
git -C "$TASK" apply "$EVIDENCE/quality-base.patch"
git -C "$TASK" apply --check "$EVIDENCE/snapshot82.patch"
git -C "$TASK" apply "$EVIDENCE/snapshot82.patch"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess
w = pathlib.Path(os.environ['TASK'])
e = pathlib.Path(os.environ['EVIDENCE'])
p = 'enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt'
(w / p).write_bytes((e / 'prctl.snapshot').read_bytes())
allowed = set((e / 'source-paths.txt').read_text().splitlines())
actual = set(subprocess.check_output(['git', 'diff', '--name-only', os.environ['BASE']], cwd=w, text=True).splitlines())
assert actual == allowed, (actual - allowed, allowed - actual)
broker = w / 'portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt'
assert hashlib.sha256(broker.read_bytes()).hexdigest() == '5b81b6f8ec50a5c88f22f4e042bc8a0b31070fef151ba301b585bd0630d8ce11'
with (e / 'candidate-source.json').open('x') as f:
    json.dump({'base': os.environ['BASE'], 'worktree': str(w), 'sourceHashes': {p: hashlib.sha256((w/p).read_bytes()).hexdigest() for p in sorted(allowed)}}, f, indent=2)
PY
git -C "$TASK" diff --check
```

Deliverable: reconstructed, pinned starting candidate. Any mismatch is reconciled before tests/commits; input custody is not a restriction on later justified changes.

### Task 2: Close the original prctl fixture correction

**Files:** Modify/test only `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt` in `TASK`; create `prctl-red.*`, `prctl-green.*`, and `prctl-native.*` receipts.

**Interfaces:** Preserve actual `internal fun prctlFixturePolicy() = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()`. `PrctlFixtureSimulationTest` consumes its definition in `BpfFilter.build`/`BpfSimulator.simulate`; both original native callbacks consume the same policy through `ContainedExecutors.wrap`.

- [ ] Read the fixture and nearest enforcer instructions. Keep the existing two-architecture simulations and actual getOrThrow exception-identity assertions; do not add a second simulator or another policy helper. Representative retained control:

```kotlin
@ParameterizedTest
@MethodSource("architectures")
fun `fixture allows executable mmap for HotSpot`(arch: Arch) {
    assertMapping(arch, prctlFixturePolicy().definition, Syscall.MMAP, 5L, NativeConstants.SECCOMP_RET_ALLOW)
}
```

- [ ] Reproduce the safe simulator RED by changing only the actual fixture helper back to its old runtime. Do **not** run the known crash-prone native fixture for RED:

```bash
python3 - <<'PY'
import os, pathlib
p = pathlib.Path(os.environ['TASK']) / 'enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt'
text = p.read_text()
line = 'internal fun prctlFixturePolicy() = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()'
assert text.count(line) == 1
p.write_text(text.replace(line, line.replace('HOTSPOT_JIT', 'NATIVE_IMAGE')))
PY
cd "$TASK"
gate prctl-red flock "$LOCK" ./gradlew :enforcer:integrationTest --tests io.mazewall.seccomp.PrctlFixtureSimulationTest
```

Expected: ten simulation cases execute; four executable mmap/mprotect cases fail across AMD64/AARCH64. Missing tests, compilation failure, or native VM death is not this RED.

- [ ] Restore the pinned implementation and run simulator GREEN:

```bash
python3 - <<'PY'
import os, pathlib
p = pathlib.Path(os.environ['TASK']) / 'enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt'
p.write_bytes((pathlib.Path(os.environ['EVIDENCE']) / 'prctl.snapshot').read_bytes())
PY
gate prctl-green flock "$LOCK" ./gradlew :enforcer:integrationTest --tests io.mazewall.seccomp.PrctlFixtureSimulationTest
```

Expected: ten PASS, zero failures/errors/skips. Strict NATIVE_IMAGE still denies executable mappings, unsafe prctl still returns the exact EPERM verdict, safe options/high-word int ABI still work.

- [ ] Run the two original native callbacks with the corrected helper:

```bash
gate prctl-native flock "$LOCK" ./gradlew :enforcer:integrationTest --tests io.mazewall.seccomp.PrctlBypassReproductionTest
```

Expected: two PASS, zero failures/errors/skips; actual entered callback, errno1/raw return−1, getOrThrow and translated cause object identity retained. No unsafe-prctl allowance or production policy change.

Commit boundary: include this fixture in the coherent source commit in Task 4; its quality prerequisites share that checkpoint. Do not force a failing one-file commit or run another broad build here.

### Task 3: Accept the existing quality repairs without another audit wave

**Files:** Initially the 24 quality paths in the map; add any justified dependency, test, or implementation path to the declared manifest. Create source freezes and static/inventory/unit/affected-native/Broker/profiler receipts. New fixtures must exercise an actual required contract or discovered defect.

**Interfaces:** Preserve `internal val SocketHelperProcess.processHandle: ProcessHandle` with `get() = process.toHandle()`. Later retirement controls use only the actual acquired handle. Existing Result consumers must rethrow unexpected/fatal objects, retain original ordinary/interruption semantics, attempt independent cleanup, and account for actual physical end.

- [ ] Inspect the selected cumulative diff against `BASE`, original 45/10/7 finding manifests and immutable review bundles. Preserve accepted F01/F02/F03/F07/F14 only when their hashes match. Run `./scripts/adkw blast-radius SocketHelperProcess` before any correction to the internal main seam. Record and verify justified production changes against the relevant design and callers before editing.
- [ ] Run current-source all-module integration analysis and the unchanged production warning ratchet:

```bash
cd "$TASK"
gate static flock "$LOCK" ./gradlew detektIntegrationTest detektWarningRatchet
```

Expected: exit0, hard error count0 in every emitted integration report, existing production ratchets pass. Archive all emitted reports, not only enforcer output.

- [ ] Compare exact integration warning signatures with the frozen report. No message/numeric normalization or changed inventory:

```bash
python3 - <<'PY'
from collections import Counter
import os, pathlib, xml.etree.ElementTree as ET
def inventory(path):
    result = Counter()
    for file in ET.parse(path).getroot().findall('file'):
        name = file.attrib['name'].replace('\\', '/')
        marker = 'enforcer/src/'
        name = marker + name.split(marker, 1)[1] if marker in name else name
        for error in file.findall('error'):
            a = error.attrib
            if a['severity'] == 'warning':
                result[(name, a['source'], a['message'], a['severity'])] += 1
            else:
                assert a['severity'] == 'info', a
    return result
reports = list(pathlib.Path('enforcer/build/reports/detekt').glob('*[Ii]ntegration*.xml'))
assert len(reports) == 1, reports
old = pathlib.Path(os.environ['LEGACY']) / 'coordinator69-static-report.xml'
# The old report contains the 45 known hard errors; its warning inventory is the baseline.
root = ET.parse(old).getroot()
for file in root.findall('file'):
    for error in list(file.findall('error')):
        if error.attrib['severity'] not in ('warning', 'info'):
            file.remove(error)
baseline = pathlib.Path(os.environ['EVIDENCE']) / 'warning-baseline.xml'
ET.ElementTree(root).write(baseline, encoding='utf-8')
added = inventory(reports[0]) - inventory(baseline)
assert not added, added
print('ZERO_ADDED_WARNINGS', reports[0])
PY
```

Expected: no added file/source/message/severity signatures. Removed hard errors/obsolete warnings are allowed. The baseline input stays unchanged; temporary warning-only extraction is evidence, not an analyzer baseline waiver.

- [ ] Run real unit-only coverage, with frozen exclusions/limits and nonempty report assertions:

```bash
gate unit flock "$LOCK" ./gradlew --init-script "$ROOT/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/testkit-isolation.init.gradle" unitCheck
```

Expected: actual enforcer≥0.82, profiler≥0.70, platform≥0.65. Orchestrator≥0.78 applies only when explicitly included; `:tools:orchestrator` is outside the default graph and this recovery does not opt it in. Preserve main class outputs, unit/owned-child exec hashes/session identities and XML instruction counts. Historical enforcer82.119655% is not a current result. Check the required default reports:

```bash
python3 - <<'PY'
import pathlib, xml.etree.ElementTree as ET
for module in ['enforcer', 'profiler', 'platform']:
    p = pathlib.Path(module) / 'build/reports/jacoco/test/jacocoTestReport.xml'
    root = ET.parse(p).getroot()
    classes = root.findall('.//class')
    count = root.find("counter[@type='INSTRUCTION']")
    assert classes and count is not None, p
    covered, missed = int(count.attrib['covered']), int(count.attrib['missed'])
    assert covered + missed > 0 and covered > 0, p
    print(module, len(classes), covered, missed, covered / (covered + missed))
PY
```

- [ ] Revalidate the six groups whose helpers changed after their historical GREENs. Select both existing routing tasks; keep each task's matching tag family nonempty:

```bash
gate affected-native flock "$LOCK" ./gradlew \
  :enforcer:integrationTest \
  --tests io.mazewall.enforcer.api.execution.DeploymentOriginNativeTest \
  --tests io.mazewall.seccomp.DedicatedProcessBaselineTest \
  :enforcer:integrationTestFreshJvm \
  --tests io.mazewall.enforcer.internal.supervisor.SupervisorOpenHelperIntegrationTest \
  --tests io.mazewall.enforcer.internal.supervisor.TraceeAuthorityIntegrationTest \
  --tests io.mazewall.seccomp.BpfHardeningTest \
  --tests io.mazewall.seccomp.SupervisorProxyIntegrationTest
```

Expected: existing causal controls and original native fixtures execute without failures/errors/required skips; no permission-shaped primary manufactured from suppressed cleanup. Verify XML contains the selected controls and native methods, not just an exit0 or routing hint. Other preserved native groups receive exact integrated full-build verification in Task 5.

- [ ] Run the existing full Broker suite and stress controls for the selected pre-extension bytes:

```bash
gate broker flock "$LOCK" ./gradlew :portals:runtime:integrationTest --tests io.mazewall.portal.ProcessBrokerIntegrationTest
```

After that receipt settles:

```bash
gate profiler-stress flock "$LOCK" ./gradlew :profiler:integrationTest --tests io.mazewall.profiler.api.ProfilerStressTest
```

Expected: original Broker suite24PASS; three stress tests PASS. Profiler integration depends on rootfulContainerTest: record all endpoint results, including any skips; missing direct Docker eligibility is a prerequisite, not stress success turned into cleanup proof.

- [ ] Freeze the final tested source hashes and obtain one scoped review of the current changed/internal-main/cross-helper range. Carry historical acceptance only for byte-identical files. Record `ACCEPTED` or concrete `CHANGES_REQUIRED` with reviewed hashes and test receipts. Inline execution still requires the repository's review path before integration; self-review cannot stand in for that approval. The initial manifest must match after the deliberate prctl RED is restored:

```bash
python3 - <<'PY'
import hashlib, json, os, pathlib
e = pathlib.Path(os.environ['EVIDENCE'])
w = pathlib.Path(os.environ['TASK'])
paths = (e/'source-paths.txt').read_text().splitlines()
current = {p: hashlib.sha256((w/p).read_bytes()).hexdigest() for p in paths}
corrections = sorted(e.glob('candidate-source-correction-*.json'))
manifest = corrections[-1] if corrections else e/'candidate-source.json'
assert current == json.loads(manifest.read_text())['sourceHashes'], 'Source changed after freeze: affected evidence must be renewed'
with (e/'accepted-source.json').open('x') as f:
    json.dump({'base': os.environ['BASE'], 'sourceHashes': current}, f, indent=2)
PY
```

Each justified correction first updates the source-path manifest and creates a new numbered freeze **before** rerunning affected checks. Use matching numbered correction labels; preserve original receipts. The review must explicitly accept the corrected hashes and renewed evidence before the accepted-source step:

```bash
python3 - <<'PY'
import hashlib, json, os, pathlib
e = pathlib.Path(os.environ['EVIDENCE'])
w = pathlib.Path(os.environ['TASK'])
paths = (e/'source-paths.txt').read_text().splitlines()
number = len(list(e.glob('candidate-source-correction-*.json'))) + 1
with (e/f'candidate-source-correction-{number:04d}.json').open('x') as f:
    json.dump({'base': os.environ['BASE'], 'worktree': str(w), 'sourceHashes': {p: hashlib.sha256((w/p).read_bytes()).hexdigest() for p in paths}}, f, indent=2)
PY
```

Do not run this correction-freeze block when no correction occurred. Formatter/source changes are corrections too; changing a manifest alone does not renew a test result.

Deliverable: all selected source accepted, current hard0/zero-added inventory/unit coverage and changed-helper evidence. A missing required check/review blocks Task 4.

### Task 4: Commit and integrate only the accepted source

**Files:** Exactly `source-paths.txt`; create source-commit/integration receipts. Root tooling/config/backlog/this plan are outside this source commit.

**Interfaces:** Consumes the accepted current source manifest/hashes and focused receipts. Produces a reviewed source commit and integrated producer revision recorded in `producer-revision`. Final consumers use that revision, not a private parent or metadata commit.

- [ ] Assert the source scope/hashes still match and the index is empty. Stage only the allowlist and invoke the unchanged hook:

```bash
cd "$TASK"
mapfile -t source_paths < "$EVIDENCE/source-paths.txt"
git add -- "${source_paths[@]}"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess
e = pathlib.Path(os.environ['EVIDENCE'])
accepted = json.loads((e/'accepted-source.json').read_text())['sourceHashes']
staged = set(subprocess.check_output(['git', 'diff', '--cached', '--name-only'], text=True).splitlines())
assert staged == set(accepted), 'Unexpected staged source scope'
assert {p: hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in accepted} == accepted
PY
gate source-commit flock "$LOCK" env \
  GIT_AUTHOR_NAME='Leanid Piliptsevich' GIT_AUTHOR_EMAIL=pilleo19@gmail.com \
  GIT_COMMITTER_NAME='Leanid Piliptsevich' GIT_COMMITTER_EMAIL=pilleo19@gmail.com \
  git -c core.hooksPath=scripts/hooks commit -m "fix: close integration fixture quality and prctl runtime blockers"
```

Expected: hook exit0 and one source-only commit parented at `BASE`; no changed tests/config from outside the allowlist. Retain `/tmp/mazewall-precommit-test.log` with this receipt. A hook failure is not authorization to bypass it.

- [ ] Under the approved scoped backlog/orchestrator review path, recheck dirty root preservation and integrate only accepted source. Require root still at `BASE`, empty staged diff, and no overlap between selected source and operator work. Generate/check a source-only patch; coordinator alone applies it:

```bash
mapfile -t source_paths < "$EVIDENCE/source-paths.txt"
SOURCE_COMMIT=$(git -C "$TASK" rev-parse HEAD)
git -C "$ROOT" diff --binary "$BASE" "$SOURCE_COMMIT" -- "${source_paths[@]}" > "$EVIDENCE/accepted-source.patch"
git -C "$ROOT" apply --check "$EVIDENCE/accepted-source.patch"
git -C "$ROOT" apply "$EVIDENCE/accepted-source.patch"
git -C "$ROOT" add -- "${source_paths[@]}"
cd "$ROOT"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess
e = pathlib.Path(os.environ['EVIDENCE'])
accepted = json.loads((e/'accepted-source.json').read_text())['sourceHashes']
staged = set(subprocess.check_output(['git', 'diff', '--cached', '--name-only'], text=True).splitlines())
assert staged == set(accepted), 'Operator or out-of-scope staged content: stop without altering it'
assert {p: hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in accepted} == accepted
PY
gate source-integrate flock "$LOCK" env \
  GIT_AUTHOR_NAME='Leanid Piliptsevich' GIT_AUTHOR_EMAIL=pilleo19@gmail.com \
  GIT_COMMITTER_NAME='Leanid Piliptsevich' GIT_COMMITTER_EMAIL=pilleo19@gmail.com \
  git -c core.hooksPath=scripts/hooks commit -m "fix: integrate reviewed fixture acceptance repairs"
```

Expected: one unchanged integration hook passes, exact source tree equals the accepted snapshot, operator work/index content preserved. Do not rewrite index bytes solely because previous tools changed the index file's hash.

- [ ] Record the actual integrated SHA and prepare the existing clean acceptance tree. Refuse dirty/unrelated/divergent state; preserve the private source branch:

```bash
git -C "$ROOT" rev-parse HEAD > "$EVIDENCE/producer-revision"
test -z "$(git -C "$ACCEPT" status --porcelain)"
REVISION=$(cat "$EVIDENCE/producer-revision")
git -C "$ACCEPT" merge-base --is-ancestor HEAD "$REVISION"
git -C "$ACCEPT" merge --ff-only "$REVISION"
test "$(git -C "$ACCEPT" rev-parse HEAD)" = "$REVISION"
```

If the clean acceptance tree cannot fast-forward safely, stop with the ownership/divergence evidence; do not reset it.

### Task 5: Run the exact integrated combined gate and core consumers

**Files:** Existing combined build/core consumer harnesses; create merge/core publication/matrix receipts and new namespaced core locks. No producer-source edits.

**Interfaces:** Consumes `producer-revision`; produces `REVISION`, `VERSION`, `REPOSITORY`, publication hashes, real combined coverage and stable JDK22/JDK25 consumer evidence.

- [ ] Enter the clean assigned acceptance tree and define the artifact binding:

```bash
cd "$ACCEPT"
REVISION=$(cat "$EVIDENCE/producer-revision")
test "$(git rev-parse HEAD)" = "$REVISION"
VERSION="0.1.0-candidate.${REVISION:0:8}"
REPOSITORY="$ACCEPT/build/fleet-consumer-repository/${REVISION:0:8}"
export REVISION VERSION REPOSITORY
```

- [ ] Run the merge gate **once** after focused acceptance:

```bash
gate combined flock "$LOCK" bash scripts/koog-build.sh \
  --init-script "$ROOT/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/testkit-isolation.init.gradle" \
  -PincludeSpringIntegration=true -- build
```

Expected: real default/core plus optional Koog/Spring graph passes. The root `check` graph already includes integrationTest, integrationTestFreshJvm, unit and kernel coverage. Verify those tasks actually executed fresh and required cases did not skip; do not replay both host kernel suites merely because an old checklist also names them. Preserve actual nonempty unit/kernel/combined class/instruction and exec identities separately.

- [ ] Stage only the exact local Maven core publications:

```bash
gate publish-core flock "$LOCK" ./gradlew --console=plain --no-build-cache \
  --no-configuration-cache --rerun-tasks -I tests/consumers/stage.init.gradle \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository
```

Expected: JAR/POM/module files in the supplied file repository, exact producer SHA/hashes recorded; no Maven Local/remote task.

- [ ] Generate new-coordinate locks, inspect the resolved graph, then verify without rewriting:

```bash
gate core-locks ./scripts/check_integration_consumers.sh \
  --repository "$REPOSITORY" --version "$VERSION" --write-locks \
  --evidence-dir "$EVIDENCE/core-lock-generation"
```

After that receipt settles:

```bash
gate core-acceptance ./scripts/check_integration_consumers.sh \
  --repository "$REPOSITORY" --version "$VERSION" \
  --evidence-dir "$EVIDENCE/core-acceptance"
```

Expected: stable Kotlin2.4.20 and actual JDK22/JDK25 rows, positive uncast Callable compilation, exactly expected negative scope diagnostics, all MazeWall dependencies from supplied Maven bytes. Graph resolution alone does not pass Tasks 6–7. Historical locks remain unchanged.

### Task 6: Close the independent Koog/Portal packaged gate

**Files:** Existing `tests/consumers/koog-portal/` and `demos/koog-contained-tool/`; create only new-coordinate locks/evidence and an ignored lock-location init script. Consume their README workflow.

**Interfaces:** Consumes the same `REVISION`, `VERSION`, `REPOSITORY`; produces generated worker package/closure, registry/native cancellation/state-loss/physical-end evidence. No new Koog service, source build substitution, or changed operation API.

- [ ] Run the real producer/plugin bootstrap, fixture publication and owning tests:

```bash
cd "$ACCEPT"
gate publish-koog flock "$LOCK" bash scripts/koog-build.sh \
  -I "$ACCEPT/tests/consumers/stage.init.gradle" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" -- \
  -PkoogNativeFixtures=true \
  :integrations:koog:test :demos:koog-contained-tool:test \
  :demos:koog-contained-tool:portalCheck \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository \
  :portals:runtime:publishAllPublicationsToConsumerBaselineRepository \
  :integrations:koog:publishAllPublicationsToConsumerBaselineRepository \
  :demos:koog-contained-tool:service-api:publishAllPublicationsToConsumerBaselineRepository \
  :koog-denied-api:publishAllPublicationsToConsumerBaselineRepository \
  :demos:koog-contained-tool:publishAllPublicationsToConsumerBaselineRepository
```

Expected: actual plugin provenance and format2 worker digest/independent closure; same core publication hashes as Task 5. Any changed bytes require diagnosed input binding, not silent replacement.

- [ ] Preserve the historical Koog lockfile by selecting a new lock location only in the independent consumer. Write this complete ignored init file to `$EVIDENCE/koog-lock-location.init.gradle`:

```groovy
gradle.beforeProject { project ->
    def version = project.providers.gradleProperty('consumerVersion').get()
    project.dependencyLocking.lockFile.set(
        project.layout.projectDirectory.file("locks/${version}/gradle.lockfile")
    )
}
```

It changes the lockfile destination, not producer sources, repositories, dependencies, tasks, strict locking, or compiler checks.

- [ ] Generate candidate Koog locks and then run the same consumer tasks without lock rewriting:

```bash
gate koog-locks flock "$LOCK" ./gradlew -p tests/consumers/koog-portal \
  -I "$EVIDENCE/koog-lock-location.init.gradle" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  --write-locks verifyOperationRoot stageFreshProbe verifyPublishedClosure
```

After review of those locks and receipt:

```bash
gate koog-consumer flock "$LOCK" ./gradlew -p tests/consumers/koog-portal \
  -I "$EVIDENCE/koog-lock-location.init.gradle" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  verifyOperationRoot stageFreshProbe verifyPublishedClosure
```

Expected: stable compiler/upstream1.3.0, correct public operation root and exact Maven paths, independent curated consumer/worker closure.

- [ ] Execute the existing disposable native consumer; it locks internally:

```bash
gate koog-native bash tests/consumers/koog-portal/run-fresh-native.sh "$JAVA_HOME/bin/java"
```

Expected: allowed bounded work, denied-resource/oversize/terminal-crash/cancellation and actual child shutdown proof; required failures fail rather than skip. Verify every owned hidden pathname is restored and any recovery conflict preserved. No extra restoration-fault suite replay unless these unchanged runner inputs actually change or this run exposes a defect.

### Task 7: Close the independent finished Boot-JAR gate

**Files:** Existing standalone Groovy build `demos/spring-boot-sandbox/{build.gradle,settings.gradle}`, optional Spring modules, and `tests/consumers/spring-boot/verify.py`; new immutable fixture evidence only.

**Interfaces:** Consumes the same producer/artifact binding; produces finished `report-application.jar`, external fixture-review sidecars, H2/denial/virtual-caller/physical-retirement evidence. Fixture trust is not production self-approval.

- [ ] Publish real Spring artifacts from the same source and repository:

```bash
cd "$ACCEPT"
gate publish-spring flock "$LOCK" ./gradlew -PincludeSpringIntegration=true \
  -I tests/consumers/spring-boot/optional-modules.init.gradle \
  -I tests/consumers/stage.init.gradle \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  :platform:publishMavenJavaPublicationToConsumerBaselineRepository \
  :enforcer:publishMavenJavaPublicationToConsumerBaselineRepository \
  :integrations:spring:mazewall-spring-boot:publishMavenJavaPublicationToConsumerBaselineRepository \
  :integrations:spring:mazewall-spring-boot-starter:publishMavenJavaPublicationToConsumerBaselineRepository \
  --no-configuration-cache --no-parallel
```

Expected: unchanged core hashes and correct optional normal integration/starter JARs.

- [ ] Build the independent executable, not a root project/source-substituted application:

```bash
gate boot-jar flock "$LOCK" ./gradlew -p demos/spring-boot-sandbox \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  bootJar --no-configuration-cache --no-parallel
```

- [ ] Execute all existing finished-JAR acceptance positives/negatives with a new evidence directory:

```bash
gate boot-native flock "$LOCK" python3 tests/consumers/spring-boot/verify.py \
  --jar "$ACCEPT/demos/spring-boot-sandbox/build/libs/report-application.jar" \
  --java "$JAVA_HOME/bin/java" --evidence "$EVIDENCE/boot-finished-jar" \
  --revision "$REVISION" --version "$VERSION" --repository "$REPOSITORY"
```

Expected: finished outer/nested-JAR/publication identity, actual java-jar dispatch, missing/stale/unsupported review rejection, singleton/virtual caller/platform body, H2/report/template effects and denials, physical end. SQL rollback remains distinct from file/network effects; no expanded classpath or required skip. Existing harness ownership fault tests need replay only for changed inputs or a concrete failure.

### Task 8: Complete authorized fresh Docker evidence

**Files:** Existing kernel/container scripts and profiler endpoint fixtures; create Docker prerequisite/result/report receipts. No permission, runtime profile, budget, or skip-condition changes.

**Interfaces:** Consumes the exact integrated source and explicit reachable rootful Docker endpoint. Produces fresh container kernel evidence or a precise infrastructure prerequisite. Host combined-native evidence comes from Task 5.

- [ ] Recheck the actual rootful endpoint and Yama without changing configuration:

```bash
cd "$ACCEPT"
gate docker-prerequisite flock "$LOCK" docker --host unix:///var/run/docker.sock info --format '{{json .}}'
```

Record `/proc/sys/kernel/yama/ptrace_scope` and actual host PID namespace/mount/fixture eligibility. Earlier rootful Docker discovery succeeded, while missing Podman socket caused four skips; that was not tested native cleanup.

- [ ] If the four required profiler endpoint cases did not execute in the exact Task 5 graph, run the existing class once with the diagnosed Docker selection:

```bash
gate profiler-rootful flock "$LOCK" ./gradlew :profiler:rootfulContainerTest \
  --tests io.mazewall.profiler.TierERootfulContainerEndpointIntegrationTest
```

Expected: required actual cleanup/retirement cases execute with zero failures/errors/skips. If an eligibility condition still fails, report its actual reason; stop unchanged retry rather than disable the condition. Do not rerun if Task 5 already supplies fresh exact-source evidence for those cases.

- [ ] Run the authorized fresh serial Docker suite:

```bash
gate docker-kernel flock "$LOCK" env MAZEWALL_CONTAINER_ENGINE=docker \
  MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./scripts/run_tests.sh
```

Expected: fresh tests, correct output ownership and applicable kernel/fresh-JVM coverage; archived reports identify the same source. Required unexpected skips/failures block the applicable gate. Positive CET on the inactive runtime remains a separately recorded hardware/runtime prerequisite; never late-enable it.

### Task 9: Close the evidence ledger and report the actual remaining gates

**Files:** Create `docs/internals/reviews/2026-10-10-integration-final-acceptance.md`; update exact new entries in `docs/internals/reviews/integration-compatibility-manifest.md`; selected new-coordinate consumer locks; owned backlog rows/issues:

- `docs/internals/backlog/issue-20261009-094632-442-match-prctl-argument-denial-integration-fixtures-t.md`
- `docs/internals/backlog/issue-20261009-120736-535-correct-45-enforcer-integration-test-quality-block.md`
- `docs/internals/backlog/issue-20261010-021238-531-repair-ten-runtime-integration-test-quality-errors.md`
- `docs/internals/backlog/issue-20261010-025904-711-repair-seven-unchanged-profiler-integration-test-q.md`
- `docs/internals/backlog/README.md` — only those owned rows/count hunks; preserve operator rows and all other unresolved issues.

**Interfaces:** Consumes exact source/test/publication/review receipts; produces separate engineering-candidate and release-prerequisite statuses. A later metadata commit does not relabel the tested producer/artifact coordinate.

- [ ] Write one table row per gate with source SHA, command, result/log/XML, artifact hashes, actual executed counts, required skips, and status (`PASS`, `FAILED`, `BLOCKED`, `PREREQUISITE`). No model-final-text or cumulative test counts as proof.
- [ ] Reconcile every original spec group using current source evidence; old ledger checkboxes are not current completion facts:

| Original contracts | Evidence to reconcile |
|---|---|
| C0/C2/C9, A01/A02/A10 | Exact stable Java/Kotlin artifact consumers, uncast Callable and negative scope checks; deliberately unresolved borrowed-wrapper migration stays an explicit gap |
| C1/C2/C3, A04/A06/A07 | Existing canonical definitions/install/native regressions in the fresh combined/Docker reports; CET-positive prerequisite separate |
| C4/C6, A03/A08/A11 | Exact required artifact/resource generation and prepared identity regressions, generated bundles and finished-JAR identity/denials; no sibling-parent grant substitution |
| C5, A01/A07/A08 | Existing selected-runtime composition/profile-backed JIT tests and truthful current examples; missing guidance/profile evidence stays an explicit gap |
| C7/C8/C9, A05/A09/A12 | Existing bounded readiness/admission/nesting/cancellation/drain/evidence tests and same-domain placement; unsupported context/borrowed surfaces do not inherit acceptance |
| P0–P5, Gate B | Existing lifecycle/readiness/state-loss/physical-end native reports plus exact published Koog worker/registry; operator environment/descriptor authority remains explicit |
| S0–S3, Gate C | Real owning-module placement/seven-propagation/security tests and finished executable Boot/JDBC/native reports; no caller-thread or mock substitution |
| R0 | Same producer local publications, stable compiler/JDK matrix and independent consumers; configured remote repository verification remains operator-owned |
| R1 | Measured boundary/negative/physical-end evidence; human usability and raw equivalent-work overhead data remain separate prerequisites unless real records exist |

If a row lacks actual implementation or evidence, record the exact missing contract/file and assign a justified corrective task. Necessary fixes are not deferred merely because they exceed the starting inventory; unavailable operator/hardware evidence remains an explicit prerequisite.

- [ ] Update only issues whose specific source/review/gate closure is proven. Do not archive all earlier issues automatically or edit six unrelated operator rows. Record preserved untested Broker extension as a deferred proof question, not a solved defect or mandatory new feature.
- [ ] Run the existing document/backlog checks:

```bash
cd "$ROOT"
./scripts/adkw check-backlog
git diff --check
```

Expected: exit0 and consistent current ledger counts. Validate the new plan for placeholders/types before execution handoff; never invent an R1 human/benchmark result.

- [ ] Through the scoped review path, stage only this plan, the evidence document, exact compatibility hunks/new-coordinate locks, and owned issue/index hunks. Use `git add -p` for mixed files and explicit paths for new files. Track this Git-ignored plan without changing the operator's ignore rules:

```bash
git add -f -- docs/superpowers/plans/2026-10-10-integration-foundation-finish.md
```

Inspect `git diff --cached` before the normal identity/hook commit. Preserve the tested producer SHA in reports even though metadata advances HEAD. No new full build is needed for documentation/lock-only closure after already-passing unchanged source gates.

## Completion and Handoff

Execution ends with one of two useful outcomes:

1. **Engineering candidate accepted:** source review, quality/inventory/real coverage, combined graph, local stable consumers, Koog, Boot and applicable fresh Docker/native gates all pass at their recorded producer inputs. Remaining CET/operator launch-profile/remote repository/human/raw-overhead prerequisites are listed; this is not automatically release-ready.
2. **Explicit blocker:** a required native/consumer/operator prerequisite, ownership issue, or indeterminate contract remains unresolved despite a grounded investigation. Preserve candidate/inputs/logs and report the exact failure plus the smallest next decision. Do not substitute coordinator cycling or manufactured fixtures for resolving the cause.

Deferred product extensions remain coroutine/WebFlux/JPA, general external processes/workspaces/credential brokers, and unattended fleet/tooling improvements. The newest Broker extension and other additional repairs are assessed on evidence and contract relevance, not a numerical ceiling. Original release requirements are retained as statuses, never deleted to make the plan appear complete.
