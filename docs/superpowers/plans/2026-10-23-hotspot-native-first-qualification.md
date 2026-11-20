---
document_type: execution_plan
base_revision: a28cbe293289f2549858aa232d9e6ebd7c4756dd
status: planned_with_external_native_execution_gates
---

# HotSpot Native-First Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended only after operator selection) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Planning and execution-style selection do not authorize inspection, native execution, provisioning, publication or fullgate.

**Goal:** Obtain one independently reviewed, fully retained public-API native outcome, or a precise blocked request, before doing more integration implementation.

**Architecture:** Reuse the completed `HotSpotObservedIntegrationTest`, its evidence helpers, the admitted collector and the existing public API. Disposable rootful Docker helpers perform only privileged namespace inspection and temporary collector-capability setup/removal; Gradle, the target JVM and the native test stay host-side and non-root. Execute the existing test once with retained JUnit scratch. Storage release, Kubescape acceptance and Detekt maintenance remain separate workstreams.

**Selected permission-only continuation:** After three preserved rejected attempts and verified source/fixture corrections, the operator directed “Justt apply, try and see” for the identified CAP_SYSLOG scope. Attempt4 uses fresh asset/helper/native outputs and adds only CAP_SYSLOG to the prior six collector capabilities; helper roles, native command/init/workload/budgets and cleanup stay unchanged. No root collector, sysctl change or automatic fifth attempt. Earlier scope records below are chronology; current bindings/results are in the native-first workspace's syslog-preparation-1/docker-permission-4 records.

**Terminal result:** Attempt4 accepted the bounded public-native fixture: Gradle0/XML1executed/0skips/failures/errors,400marked operations/380useful routes/176compiled marked events. Target1896794 exited0, both helpers removed and capabilities cleared, kptr_restrict1 unchanged. All four grants consumed; independent outcome review and storage/Kubescape acceptance remain separate. The original failed attempts/earlier checkpoints below are preserved, not overwritten by this result.

**Tech Stack:** Linux-amd64; pinned Temurin25.0.4.1+1-LTS target with ordinary tiered/G1; existing Kotlin/JUnit5.10.2 fixture; Gradle9.6.1; local rootful Docker at `unix:///var/run/docker.sock`; standard shell/Python for checked capture and evidence custody.

**Spec:** [Governing design, A/A3](../specs/2026-10-18-kubescape-agentless-hotspot-design.md), interpreted with the [observed-profile contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md), [current handoff](../../internals/reviews/2026-10-23-hotspot-runtime-qualification.md), and final source approval at `.superpowers/sdd/2026-10-23-hotspot-release-and-runtime-qualification/final-review.md`.

## Global Constraints

The following requirements are copied verbatim from the governing design:

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

Current interpretation and execution constraints:

- Observed admission is already separately approved. This test explicitly selects `otel_hotspot25_observed_v1`, collector3/wire3/capture ABI2 and ordinary tiered/G1. Strict2, AUTO and their interpreter/Serial requirements remain unchanged. Every observation stays policy-ineligible and globally incomplete.
- Work inline in the current checkout. No worktree, branch switch, stage, commit, stash, push, merge, publication or retained-workspace deletion. These constraints override generic frequent-commit workflow advice.
- Preserve historical strict-eight FAILED/INCOMPLETE, consumed HOST/N3 grants, all failed receipts and unknown resource owners. The approved retained-bundle read/verify/copy exception is complete; it grants no access to other historical runtime/build/export resources.
- No collector, public API, workload or repository build-wiring change is planned. The sole planned privilege mutation is temporary file capabilities on the fresh private collector copy via the Docker bracket below. No new shipped launcher, transport hook, lifecycle framework, Kubernetes inspector or speculative CI.
- A native grant covers exactly one selected `:profiler:test` invocation, one unchanged400/4/20 workload and at most one collector session. Public defaults are10s readiness/60s session/2s finish; the companion's15s window is not this public API's default. Existing target-ready/marked-work waits are30s each and target-exit wait5s; do not enlarge them.
- No automatic retry, BPF/perf preflight, negative/churn/unload case, other provisioning, download, build/load/pull of images, cluster/API/CRI operation or fullgate. Only the two scoped permission helpers may be created/started/removed after the amended execution scope is selected. No `--privileged`, Docker-socket/whole-host mount, sysctl change or automatic security-profile relaxation.
- Use preprovisioned tools/caches. `--offline` does not prevent a missing Gradle wrapper distribution from being downloaded by the wrapper; require cached Gradle9.6.1/toolchains before invoking it. Do not change HOME, proxy settings or credentials to work around a missing input.
- Never dump the complete environment or `/proc/*/environ`. Record only the explicitly selected nonsecret inputs. Ordinary JVM flags must not be altered to force compiled coverage.

---

## Scope and completed inputs

This is a focused **A/public-consumer qualification plan**, not another A/B/C/D implementation plan. The governing design already separates those subprojects. The [release/runtime plan](2026-10-23-hotspot-release-and-runtime-qualification.md) retains storage publication/pinning and server/live acceptance; [issue528](../../internals/backlog/issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md) retains independent maintenance scope.

Do not redo the approved storage review, the previous26-path node-agent contribution, the Task3 retention implementation or their terminal suites. Source approval is0Critical/0Important with one nonblocking native-coordinator complexity/length follow-up. Current helper tests passed15/15; unchanged consumer/backend/identity receipts retain their own coverage. The pre-fix Detekt run reproduced16 out-of-scope errors; that is neither a current count nor fullgate clearance.

M is `/home/leanid/Documents/code/java/jseccomp`, currently HEAD `a28cbe293289f2549858aa232d9e6ebd7c4756dd` **plus dirty/untracked source**. HEAD alone never identifies this build. The old plan's frozen manifests/reports below are named read-only source inputs; do not crawl its historical outputs or rewrite its ledger.

## Current execution result — one attempt consumed, rejected

The operator subsequently said “ok, implement”. Docker setup/cleanup helpers
exited0 and were removed; exact host capability setup/removal checks passed.
The single host-side native test ran2026-10-23T21:54:48–21:56:20Z and failed
before Ready with `mapped JVM file identity changed` (Gradle1, XML1failure,
compound collector137). The fixture reported no pending ownership and target
exit0. Originals are retained under P/private/{docker-permission-1,native-attempt-1}.
The one-attempt grant is consumed: no retry, source correction or collector rebuild.
The [result](../../internals/reviews/2026-10-23-hotspot-public-native-attempt.md)
and [issue185](../../internals/backlog/resolved/issue-20261023-215710-185-investigate-hotspot-mapped-jvm-device-mismatch-on-.md)
retain the maps-versus-stat device mismatch and unproven filesystem mechanism.
Independent outcome review remains pending; all other workstreams stay separate.

## Earlier resume checkpoint — Docker amendment, not execution

P is the existing `.superpowers/sdd/2026-10-23-hotspot-native-first-qualification/`. Inline execution was already selected; do not restart workflow selection or repeat completed discovery/source suites/asset verification merely to resume.

- `P/private/asset-reuse-1/prepared-native-inputs.json` binds the prepared read-only JVM/assets and unused `private/native-attempt-1` output. JVM extraction from the pinned local image and original A/B/copied observed3 full-byte/OCI verification are complete.
- Manifest SHA256 is `453ff8c2f873b17daa61b0e8ac8f1e64748ceca0aff7649958f8dadd021c2d50`; collector SHA256 is `19bdcfb20281588591b81d6dab8b9c6505b1187390ad68dd81750fd3f9aef347`. Existing provenance and failed/rejected inputs stay retained; local integrity is not independent attestation or native qualification.
- The prior exact host scope was approved, but `sudo -n readlink` exited1 with `sudo: interactive authentication is required`. No readlink child, setcap assignment/removal or native invocation ran. Preserve `P/private/native-permission-1/`, including its frozen approved request; do not rewrite it as Docker approval or retry sudo.
- Native invocation count remains **0**, native/Gradle exits **null**, and the attempt directory is absent. Original passive `/proc/1/ns/pid` EACCES13 leaves initial-host provenance uncertified.
- This amendment replaces host-sudo setup/removal with Docker helpers. It authorizes **plan edits only**: no container or privileged/native command runs while planning. On later execution, discover/bind a suitable cached immutable helper image and explicitly select the amended helper scope; do not ask for prepared input paths/hashes, sudo authentication or another native-attempt budget.

## File and artifact responsibilities

| Path | Responsibility |
|---|---|
| `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedIntegrationTest.kt` | Read unchanged; one opt-in invocation, corpus, session close/target hold and retained outcomes |
| Adjacent `HotSpotObservedAcceptanceEvidence.kt` and `HotSpotObservedTargetIdentity.kt` | Read unchanged; original maps, tuple binding, compiled-PC proof, typed failure fields |
| `profiler/src/main/kotlin/io/mazewall/profiler/api/{HotSpotOptions,HotSpotProfilingSession,StructuredProfileEvidence}.kt` | Read unchanged; actual public defaults and evidence/ownership limits |
| `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/{HotSpotAssets,HotSpotBackend}.kt` | Read unchanged; host full-byte/OCI verification precedes collector launch |
| `experiments/hotspot-agentless/src/ObservedWorkload.java`, `profiler/build.gradle.kts` | Read unchanged;400/4/20, `runtime.txt`/four TSVs, resource wiring and opt-in cache bypass |
| Existing execution workspace `P` | Keep selection/request/inspection records and ledger current; preserve all original receipts |
| New `P/private/docker-permission-1/` | Exclusively acquired0700 helper evidence: selected image/daemon, host bindings, create/start/wait/inspect/remove statuses, CIDs and capability checks; separate from native output |
| New operator-selected `NATIVE_ATTEMPT_OUTPUT` | One attempt's init script, command logs/status, original XML and retained `junit/` tree; never reused |
| `docs/internals/reviews/2026-10-23-hotspot-public-native-attempt.md` | Create a truthful blocked/executed result; filename identifies this plan, actual timestamps identify execution |
| `profiler/HOTSPOT_AGENTLESS.md` | Amend only after an actual outcome; never replace “not yet run” with a success claim on preparation alone |
| `docs/internals/reviews/2026-10-23-hotspot-runtime-qualification.md` | Append this lane's reviewed outcome without rewriting the earlier dated preparation snapshot or other lanes |

No N/S source change belongs to this plan. The source-correction packages remain distinct from native evidence.

## Authority checkpoints

| Boundary | Required before crossing |
|---|---|
| Local source/request preparation | Execution selection; no external input is inferred |
| Read selected assets/JVM/host/security state | Actual input selections and approved passive-inspection paths/commands/output |
| Docker namespace read / collector setcap / removal | Amended helper scope selected with local daemon, cached immutable image, exact file, helper capabilities and failure/cleanup custody; prior sudo approval is history, not approval for containers |
| Invoke native test | Reviewed concrete request and one exact run/retention/cleanup grant |
| Repair permissions, retry, run controls or collect missing transport | A new scoped decision, not the failed attempt's authority |
| Publish storage, download modules, run server/live gates or fullgate | Separate workstream selection and exact applicable authority |

### Task 1: Bind actual inputs and obtain one reviewable native request

**Files:** Read the source paths above, the current handoff and named final source review. Create `P/native-selection.md`, `P/native-inspection-request.md`, `P/native-inspection-result.md` and `P/native-run-request.md`. These are operator-custody Markdown records, not a new trust schema or fabricated BuildReceipt.

**Interfaces:**
- Consumes current Task3 source manifest `.superpowers/sdd/2026-10-23-hotspot-release-and-runtime-qualification/private/task-3-fix-1-artifacts/file-manifest.json` and protected-main manifest `task-3-protected-main-hashes.json` in that workspace.
- Produces an actual selection of `MW_HOTSPOT_OBSERVED_JAVA`, `MW_HOTSPOT_ASSET_TEST_DIR`, `MANIFEST_SHA256`, `NATIVE_LIBJVM`, `NATIVE_REVIEW_OUTPUT`, `NATIVE_ATTEMPT_PARENT` and `NATIVE_ATTEMPT_OUTPUT`, plus an operator grant referring to the exact Task2 command/init script. No default values are selected by this plan.

- [ ] **Step 1: Confirm source identity without repeating suites.** Record current status/HEAD/branch and hashes of the fixture, helper, identity reader, workload, `profiler/build.gradle.kts`, wrapper properties and version catalog in P. Compare the approved four Task3 files and protected main bytes:

```python
from pathlib import Path
import hashlib, json

old = Path('.superpowers/sdd/2026-10-23-hotspot-release-and-runtime-qualification')
manifest = json.loads((old / 'private/task-3-fix-1-artifacts/file-manifest.json').read_text())
for name, entry in manifest['source_files'].items():
    assert hashlib.sha256(Path(name).read_bytes()).hexdigest() == entry['current_sha256'], name
protected = json.loads((old / 'task-3-protected-main-hashes.json').read_text())
actual = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in Path('profiler/src/main/kotlin/io/mazewall/profiler').rglob('*') if p.is_file()}
assert actual == protected, 'Changed production source needs a scoped review decision'
```

Any mismatch is a source-change report, not permission to restore user work or rerun every old suite. Re-review/recheck only an actually invalidated boundary under its selected scope.

- [ ] **Step 2: Bind actual selections before any new environmental probe.** Reuse the completed checkpoint and `prepared-native-inputs.json`; the executor discovers the remaining cached helper image/tool facts rather than asking the operator for paths/hashes. `native-selection.md` records these facts with their receipt references. If a required fact is absent, record **BLOCKED_NOT_RUN** with its exact name; no exit code or native receipt is manufactured.

| Selection | Required content |
|---|---|
| Direct host | Actual host identity, Linux-amd64/kernel/boot, initial host procfs/cgroup view, selected non-root execution UID and its custody owner |
| Tooling | Actual cached Gradle9.6.1, build/test JDK and dependencies; selected checkout/snapshot; no downloads or startup compiler for the collector |
| Target JVM | Absolute executable and expected libjvm paths, independently supplied provenance/digests/GNU build ID; target remains ordinary tiered/G1 without agents or forced compilation |
| Observed assets | Absolute immutable/read-only directory, independently trusted manifest SHA256, actual collector binary/source/module/compiler identities and matching qualification receipt if reused |
| Permissions | Selected Docker-only setup/removal scope below; exact host collector capabilities and proc/maps/cgroup/BPF/perf/uprobe/exec-barrier/process-memory authority; passive observations are not proof of child capabilities or successful native calls |
| Permission helper | Cached Linux-amd64 image pinned by full local `sha256:` image ID, with `/bin/sh`, `cat`, `readlink`, `/usr/sbin/setcap` and `/usr/sbin/getcap`; inspect image provenance/configuration, reject declared volumes, override entrypoint/working directory and disable healthchecks. No pull/build/install or guessed tag. Record actual Docker versions/rootful daemon evidence at execution. |
| Custody | New private inspection/attempt outputs, owner and retention capacity; actual owner who will retain/reconcile a held target or collector if cleanup is unknown |
| Transport | Explicitly select bounded public record/summary evidence plus diagnostics for this public-API claim, or declare the required full-transcript arrangement missing and block before execution |

The compatibility constants `libjvm SHA256=5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c` and build ID `e4dad22744e32e4d71755f4a58c5cf1f1d336f87` are not trust receipts. Do not recover an installation/hash/owner from consumed candidates or old Ready evidence.

- [ ] **Step 3: Present and, only if approved, execute bounded passive inspection.** Acquire `NATIVE_REVIEW_OUTPUT` exclusively under a selected owned0700 nonsymlink parent. Reject an existing output:

```python
from pathlib import Path
import os, stat

out = Path(os.environ['NATIVE_REVIEW_OUTPUT'])
assert out.is_absolute() and '..' not in out.parts
parent = out.parent
for component in (parent, *parent.parents):
    assert not component.is_symlink(), component
info = parent.stat()
assert parent.is_dir() and info.st_uid == os.geteuid()
assert stat.S_IMODE(info.st_mode) == 0o700
out.mkdir(mode=0o700)
```

In a fresh bash with noclobber enabled, capture each selected command's actual stdout/stderr/status; do not pipe through `tee` or turn a denial into a warning:

```bash
set -eC
: "${NATIVE_REVIEW_OUTPUT:?new approved private inspection output}"
: "${MW_HOTSPOT_OBSERVED_JAVA:?selected absolute target executable}"
: "${NATIVE_LIBJVM:?selected absolute expected libjvm file}"
: "${MW_HOTSPOT_ASSET_TEST_DIR:?selected immutable observed assets}"
capture() {
    local label=$1 code
    shift
    if "$@" > "$NATIVE_REVIEW_OUTPUT/$label.stdout" 2> "$NATIVE_REVIEW_OUTPUT/$label.stderr"; then
        code=0
    else
        code=$?
    fi
    printf '%s\n' "$code" > "$NATIVE_REVIEW_OUTPUT/$label.exit-code"
    return "$code"
}
capture uid id -u
capture kernel uname -srm
capture boot cat /proc/sys/kernel/random/boot_id
capture mounts cat /proc/self/mountinfo
capture cgroup cat /proc/self/cgroup
capture inspection-process-status cat /proc/self/status
capture yama cat /proc/sys/kernel/yama/ptrace_scope
capture perf cat /proc/sys/kernel/perf_event_paranoid
capture bpf cat /proc/sys/kernel/unprivileged_bpf_disabled
capture jvm-files sha256sum -- "$MW_HOTSPOT_OBSERVED_JAVA" "$NATIVE_LIBJVM"
capture jvm-notes readelf -n -- "$NATIVE_LIBJVM"
capture manifest sha256sum -- "$MW_HOTSPOT_ASSET_TEST_DIR/manifest.json"
```

Each command/path must be covered by the inspection approval. Missing tools/files or denied reads preserve a rejected inspection; do not install tools, change Yama/sysctls/file capabilities, become root or run a helper/preflight to fill the gap. `/proc/self/status` here describes the inspection command, not the later test JVM/collector. Initial-host procfs/cgroup provenance and authority must still be bound independently. A native read denial can remain a risk of the single authorized attempt; it is not pre-certified away by this inspection.

- [ ] **Step 4: Review the concrete one-attempt request and amended permission bracket.** Bind selections/inspection results, source/init-script hashes, exact native command, immutable helper image, helper commands/capabilities/CID lifecycle below, ordinary inherited JVM configuration and cleanup/hold custody. Preserve the already approved, unused one-invocation bound; explicitly select the new Docker mechanism before execution. No retry or extra native probe. Timeout/denial does not authorize killing/deleting a target whose collector remains pending. Include the temporary test-JVM-only scratch override; exclude public hooks, collector rebuilds, target startup/GC/compiler changes and full transport claims.

`HotSpotAssets.load` in the host library rehashes every manifest file and validates the complete local OCI graph before `HotSpotBackend` launches the collector. The external trusted manifest digest supplies origin binding; the helper must not attest its own integrity. Preserve asset immutability through verification and execution. The public options have **no expected-manifest-SHA parameter**.

**Gate:** A reviewed actual request awaits/is covered by exact operator approval, or a named blocked selection/inspection result. Storage release is not needed. No new implementation or source-test cycle is required for this request-only deliverable.

### Task 2: Execute the existing native fixture once and freeze all evidence

**Files:** Read unchanged native fixture/public implementation. Create helper receipts only in the new private Docker-permission output and native evidence only inside `NATIVE_ATTEMPT_OUTPUT`, including `attempt.init.gradle`, logs/status, original XML and `junit/`. Change only the fresh collector's capability metadata during the approved bracket; never its payload, original assets or JDK. Do not change repository Kotlin/Java/Gradle files.

**Interfaces:**
- Consumes Task1's reviewed actual selections and one invocation/retention/cleanup grant.
- Calls existing `MazewallProfiler.observeHotSpot(HotSpotObservationTarget, HotSpotOptions)` only through `io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest`.
- Produces original command status/logs/XML, original retained scratch and actual accepted/rejected/unexecuted observations for Task3. No imported capture or synthetic profile supplies runtime evidence.

- [ ] **Step 0: Run the Docker-only permission setup before acquiring a native attempt.** Recheck the existing source/input/host/boot/UID1000 bindings, ordinary JVM options, private ancestry, regular nonsymlink single-link collector, full collector hash and absent capability metadata. Record device/inode/UID/mode/link-count for exact identity checks before each helper and after its removal. Exclusively acquire `P/private/docker-permission-1/` using the same0700 ownership rules as Task1. No old helper may be pending/unknown. Retain actual stdout/stderr/exit status for every command below, including failed creates and evidence-write failures.

Inspect the selected cached image and explicit local rootful daemon without starting another probe container. Require the full immutable image ID and no declared image volumes. Use these common creation arguments; `HELPER_OUTPUT` is the new absolute evidence directory and `DOCKER_HELPER_IMAGE` is the actually inspected image ID, not a placeholder tag. Host namespace/boot values must still match the existing host receipts:

```bash
set -eC
: "${HELPER_OUTPUT:?new owned private Docker-permission output}"
: "${DOCKER_HELPER_IMAGE:?inspected cached immutable image ID}"
: "${MW_HOTSPOT_ASSET_TEST_DIR:?prepared observed assets}"
[[ "$DOCKER_HELPER_IMAGE" =~ ^sha256:[0-9a-f]{64}$ ]] || exit 2
COLLECTOR="$MW_HOTSPOT_ASSET_TEST_DIR/mw-hotspot-collector"
COLLECTOR_CAPS=cap_bpf,cap_perfmon,cap_sys_admin,cap_sys_ptrace,cap_sys_resource,cap_dac_read_search,cap_syslog=ep
EXPECTED_BOOT=$(cat /proc/sys/kernel/random/boot_id)
EXPECTED_USER_NS=$(readlink /proc/self/ns/user)
EXPECTED_PID_NS=$(readlink /proc/self/ns/pid)
docker=(/usr/bin/docker --host=unix:///var/run/docker.sock)
common=(--pull=never --network=none --read-only --cap-drop=ALL
    --userns=host --user=0:0 --workdir=/ --restart=no --no-healthcheck
    --security-opt=no-new-privileges=true --entrypoint=/bin/sh
    --mount "type=bind,src=$COLLECTOR,dst=/mw-hotspot-collector"
    --label "io.mazewall.qualification=$HELPER_OUTPUT"
    --env "EXPECTED_BOOT=$EXPECTED_BOOT" --env "EXPECTED_USER_NS=$EXPECTED_USER_NS")
"${docker[@]}" create "${common[@]}" --cidfile "$HELPER_OUTPUT/setup.cid" \
    --label io.mazewall.helper-phase=setup --pid=host \
    --cap-add=SETFCAP --cap-add=SYS_PTRACE --cap-add=DAC_READ_SEARCH \
    --env "EXPECTED_PID_NS=$EXPECTED_PID_NS" --env "COLLECTOR_CAPS=$COLLECTOR_CAPS" \
    "$DOCKER_HELPER_IMAGE" -eu -c '
for tool in /bin/sh cat readlink /usr/sbin/setcap /usr/sbin/getcap; do
    command -v "$tool" >/dev/null
done
uid_seen=no
while read -r key real effective saved fs rest; do
    case "$key" in Uid:) test "$real:$effective:$saved:$fs" = 0:0:0:0; uid_seen=yes;; esac
done </proc/self/status
test "$uid_seen" = yes
cat /proc/self/status
test "$(cat /proc/sys/kernel/random/boot_id)" = "$EXPECTED_BOOT"
for ns in /proc/self/ns/user /proc/1/ns/user; do
    actual=$(readlink "$ns"); printf "%s %s\n" "$ns" "$actual"
    test "$actual" = "$EXPECTED_USER_NS"
done
for ns in /proc/self/ns/pid /proc/1/ns/pid; do
    actual=$(readlink "$ns"); printf "%s %s\n" "$ns" "$actual"
    test "$actual" = "$EXPECTED_PID_NS"
done
/usr/sbin/setcap "$COLLECTOR_CAPS" /mw-hotspot-collector
/usr/sbin/setcap -v "$COLLECTOR_CAPS" /mw-hotspot-collector
/usr/sbin/getcap /mw-hotspot-collector
'
```

The bind is read/write solely to permit that file's xattr mutation; no host directory, Docker socket or whole-host filesystem is mounted. The helper never executes the collector. `--userns=host` is required for host-effective capabilities, but image ownership can still reflect daemon remapping: check actual helper UID/user namespace, not the flag alone. Setup alone uses the host PID namespace and the three listed helper capabilities; it does not gain SYS_ADMIN/BPF/PERFMON/SYSLOG. Its host-PID/ptrace exposure and the collector's seven capabilities, including broad SYS_ADMIN/process-memory and privileged kernel-log/address access/control, remain security-sensitive. Keep default Docker seccomp/LSM protections; a denied observation is a retained failure, not permission for `unconfined`.

**Checked lifecycle, not a blind command sequence:** Before starting setup, arm a host-side `try/finally`/EXIT cleanup obligation covering every later setup, native and evidence-persistence failure. Every Docker call, including image inspection and cleanup, uses the explicit local-daemon `docker` array above. For each helper, retain create stdout/stderr/status and its full64-hex CID in the new cidfile. Inspect that exact CID's image, ownership/phase labels, bind and security configuration before starting it. Capture `"${docker[@]}" start --attach "$CID"`, then `"${docker[@]}" wait "$CID"` (CLI status **and printed container exit code**) and a final `"${docker[@]}" inspect` identity/state observation. Require stopped state, wait/inspect agreement and exit0 for success. Freeze receipts before `"${docker[@]}" rm "$CID"` **without force**, then retain removal status. No `--rm`, name-based search/kill, prune, anonymous volumes or deletion of an unacquired CID.

If acquisition/CID/state is unknown, preserve known evidence and stop: do not start native work or another helper while a setup helper could still regrant capabilities. Cleanup remains due but blocked pending exact owner reconciliation. A known stopped failed helper may be removed by its acquired CID; its failure still rejects setup. A running helper is not declared retired because attach/wait failed. No automatic stop/kill/retry is granted.

After setup is confirmed stopped and removed, recheck collector identity/hash on the host, capture `/usr/sbin/setcap -v "$COLLECTOR_CAPS" "$COLLECTOR"` and `/usr/sbin/getcap "$COLLECTOR"`, and require successful exact metadata verification. These host commands only read/verify; no host sudo/setcap write occurs. Any failed precondition leaves the native count0 and enters the cleanup obligation. Only a fully retained successful setup permits Steps1–6.

- [ ] **Step 1: Recheck bindings and exclusively acquire the attempt output.** Require source/input/host identity still matches the approved request. All environment values below are actual exported selections, not authority by themselves. From M:

```python
from pathlib import Path
import os, stat

parent = Path(os.environ['NATIVE_ATTEMPT_PARENT'])
out = Path(os.environ['NATIVE_ATTEMPT_OUTPUT'])
assert parent.is_absolute() and out.is_absolute() and out.parent == parent
assert '..' not in parent.parts and '..' not in out.parts
for component in (parent, *parent.parents):
    assert not component.is_symlink(), component
info = parent.stat()
assert parent.is_dir() and info.st_uid == os.geteuid()
assert stat.S_IMODE(info.st_mode) == 0o700
out.mkdir(mode=0o700)                 # exclusive: existing attempts reject
(out / 'junit').mkdir(mode=0o700)
xml = Path('profiler/build/test-results/test/TEST-io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest.xml')
if xml.exists():
    with (out / 'pre-existing-public-native-test.xml').open('xb') as copy:
        copy.write(xml.read_bytes())
```

An old XML result is historical input, never the new attempt. Do not remove it to manufacture freshness. No later Gradle command may run until the current invocation's XML/scratch have been frozen and classified.

- [ ] **Step 2: Create the reviewed scratch-retention init script exclusively.** The existing test does **not** consume `NATIVE_ATTEMPT_OUTPUT`. A private Gradle init script sets `java.io.tmpdir` for `:profiler:test`'s JVM only, so JUnit's retained directory is inside this output. The target is launched by its unchanged literal `ProcessBuilder` and does not inherit the parent JVM's system properties; no target flags or repository wiring change:

```bash
set -C
cat > "$NATIVE_ATTEMPT_OUTPUT/attempt.init.gradle" <<'GROOVY'
import org.gradle.api.tasks.testing.Test
def output = System.getenv('NATIVE_ATTEMPT_OUTPUT')
if (output == null) throw new GradleException('Missing owned attempt output')
def scratch = new File(output, 'junit')
if (!scratch.isAbsolute() || !scratch.isDirectory()) throw new GradleException('Missing owned JUnit scratch')
allprojects {
    if (path == ':profiler') {
        tasks.withType(Test).configureEach {
            if (name == 'test') systemProperty 'java.io.tmpdir', scratch.absolutePath
        }
    }
}
GROOVY
sha256sum -- "$NATIVE_ATTEMPT_OUTPUT/attempt.init.gradle" > "$NATIVE_ATTEMPT_OUTPUT/init-script.sha256"
```

Check the bytes against the approved request before continuing. This is an execution artifact, not a new shipped script or permission to extend test logging/API surfaces. Its sole configuration delta is the test JVM's temporary-directory property. If actual JUnit scratch appears elsewhere, retain the evidence and reject custody; do not search protected `/tmp` trees or rerun automatically.

- [ ] **Step 3: Check the independently trusted manifest immediately before invocation.** With selected assets immutable, save the expected digest and actual checked result:

```bash
set -C
: "${MANIFEST_SHA256:?independently trusted selected manifest digest}"
[[ "$MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]] || exit 2
printf '%s  %s\n' "$MANIFEST_SHA256" "$MW_HOTSPOT_ASSET_TEST_DIR/manifest.json" > "$NATIVE_ATTEMPT_OUTPUT/manifest.expected.sha256"
if sha256sum --check --strict "$NATIVE_ATTEMPT_OUTPUT/manifest.expected.sha256" \
    > "$NATIVE_ATTEMPT_OUTPUT/manifest-check.stdout" 2> "$NATIVE_ATTEMPT_OUTPUT/manifest-check.stderr"; then
    printf '0\n' > "$NATIVE_ATTEMPT_OUTPUT/manifest-check.exit-code"
else
    code=$?
    printf '%s\n' "$code" > "$NATIVE_ATTEMPT_OUTPUT/manifest-check.exit-code"
    exit "$code"
fi
```

A failed precondition means native invocation NOT RUN, not a failed native session. Retain it and stop. No standalone Java/collector probe is included in this authority.

- [ ] **Step 4: Invoke exactly once and retain the real exit code.** Tools/caches and the completed Docker permission bracket's setup must already exist. Run Gradle/test/target directly on the host as UID1000, outside every helper namespace; no sudo/container wrapper or `PR_SET_PTRACER` target change. Record actual start/end UTC timestamps in separate exclusive files. The init script and named environment values are part of the approved recipe:

```bash
set -C
date -u +%Y-%m-%dT%H:%M:%SZ > "$NATIVE_ATTEMPT_OUTPUT/started-utc.txt"
if MW_HOTSPOT_OBSERVED_ACCEPTANCE=true MW_HOTSPOT_NATIVE_REFUSAL_TEST=false \
    ./gradlew --offline --no-build-cache --no-configuration-cache \
    --init-script "$NATIVE_ATTEMPT_OUTPUT/attempt.init.gradle" \
    :profiler:test --tests io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest \
    > "$NATIVE_ATTEMPT_OUTPUT/gradle.stdout" 2> "$NATIVE_ATTEMPT_OUTPUT/gradle.stderr"; then
    code=0
else
    code=$?
fi
printf '%s\n' "$code" > "$NATIVE_ATTEMPT_OUTPUT/gradle.exit-code"
date -u +%Y-%m-%dT%H:%M:%SZ > "$NATIVE_ATTEMPT_OUTPUT/finished-utc.txt"
```

Keep `MW_HOTSPOT_OBSERVED_JAVA`, `MW_HOTSPOT_ASSET_TEST_DIR` and `NATIVE_ATTEMPT_OUTPUT` exported as selected in Task1. Do not leave the acceptance opt-in enabled for another command. The existing build disables test up-to-date/cache reuse when it is true; still inspect the actual task result. A wrapper/configuration/compilation failure or skipped test is not executed native acceptance. No retry follows an error, empty compiled sample set or rejected permission. If the Gradle command does not terminate, retain active check ownership and seek the exact interrupt/recovery decision; do not silently kill its tree, start another test or call timeout retirement.

- [ ] **Step 5: Freeze original XML before interpreting pass/fail.** Run regardless of the Gradle status. This copies original bytes exclusively; missing XML is recorded without supplying a fake suite:

```python
from pathlib import Path
import os
import xml.etree.ElementTree as ET

out = Path(os.environ['NATIVE_ATTEMPT_OUTPUT'])
source = Path('profiler/build/test-results/test/TEST-io.mazewall.profiler.hotspot.HotSpotObservedIntegrationTest.xml')
if not source.exists():
    with (out / 'xml-observation.txt').open('x') as record:
        record.write('missing; execution/freshness not established\n')
else:
    raw = source.read_bytes()
    with (out / 'public-native-test.xml').open('xb') as copy:
        copy.write(raw)
    suite = ET.fromstring(raw)        # only after original bytes are retained
    counts = {key: int(suite.attrib.get(key, '0')) for key in ('tests', 'skipped', 'failures', 'errors')}
    with (out / 'xml-observation.txt').open('x') as record:
        record.write(str(counts) + '\n')
```

Original failure XML must survive parse/assertion failure too. Establish fresh execution from the actual Gradle task/log, original suite/testcase identity, and newly acquired JUnit scratch/target evidence together; neither timestamps, changed bytes nor an old0-failure XML alone proves freshness. List only this output's `junit/` children, record the actual directory containing `ObservedWorkload.java`, and retain the entire tree in place with `@TempDir(cleanup=NEVER)`. No broad temporary-directory search or second test run to discover its path.

- [ ] **Step 6: Freeze available evidence and all owners.** Retain originals before any interpretation, including rejected/partial outcomes:

| Artifact in the actual JUnit scratch | Interpretation |
|---|---|
| `runtime.txt`, `ObservedWorkload.java`, `oracle-0.tsv` through `oracle-3.tsv`, `target.stdout`, `target.stderr`, `ready`, `done` when present | Actual flags/GC/corpus/worker oracle; missing files remain missing |
| `target-identity.json`, `target-maps-before`, `target-maps-after` | Independently acquired tuple and original bounded maps; compare before/after and actual mapped libjvm to selected bytes |
| `evidence.json` | Bounded public report, potentially from a subsequently rejected attempt; not complete stdout |
| `compiled-marked-event-ids.txt`, `compiled-marked-routes.tsv`, `compiled-marked-frames.jsonl` | Actual sampled IDs, route counts and quality fields; empty/missing is not compiled proof |
| `collector-before-close.stderr`, `collector-after-close.stderr` and matching discarded counts | Returned session's diagnostic phases; failed attach has only exception snapshots |
| `pending-ownership-after-close.txt`, `target-retirement.txt`, `attempt-outcome.txt` | Actual cleanup observations, not authority to override thrown failures or unknown owners |
| `failure.txt`, `root-failure-pending-ownership.txt`, actual native-error/partial files and numbered variants | Original compound failure/errno/diagnostic/partial fields, including generic-root unknown ownership |

Hash available regular nonsymlink evidence files after the invocation is terminal; record missing/denied/unstable files instead of fabricating them. A digest is byte identity, not resource retirement. If a target/collector is held or unknown, name its known identity and custody owner, retain the target/evidence, and stop. Do not issue a release/kill/delete merely because the Gradle command ended. Any independently required additional physical observation or cleanup outside the existing owner needs its approved scope.

- [ ] **Step 7 / mandatory finally: Remove future-execution capabilities with a second helper.** Attempt this even if setup failed after starting, the native invocation was unreached/failed, or evidence persistence failed, provided the setup CID/retirement is known and the collector identity still matches. Recheck identity/hash before binding; do not mutate a replacement path. Use the same selected image/common arguments and checked CID lifecycle, but only SETFCAP and **no `--pid=host`**, SYS_PTRACE or DAC_READ_SEARCH:

```bash
"${docker[@]}" create "${common[@]}" --cidfile "$HELPER_OUTPUT/cleanup.cid" \
    --label io.mazewall.helper-phase=cleanup --cap-add=SETFCAP \
    "$DOCKER_HELPER_IMAGE" -eu -c '
uid_seen=no
while read -r key real effective saved fs rest; do
    case "$key" in Uid:) test "$real:$effective:$saved:$fs" = 0:0:0:0; uid_seen=yes;; esac
done </proc/self/status
test "$uid_seen" = yes
cat /proc/self/status
test "$(cat /proc/sys/kernel/random/boot_id)" = "$EXPECTED_BOOT"
test "$(readlink /proc/self/ns/user)" = "$EXPECTED_USER_NS"
before=$(/usr/sbin/getcap /mw-hotspot-collector)
printf "%s\n" "$before"
if test -n "$before"; then /usr/sbin/setcap -r /mw-hotspot-collector; fi
after=$(/usr/sbin/getcap /mw-hotspot-collector)
printf "%s\n" "$after"
test -z "$after"
'
```

After confirmed cleanup exit/stopped/removal, capture host `getcap` and require both exit0 **and empty stdout**; recheck the same collector hash/identity. Assignment never attempted is recorded separately from successful revocation. If removal, observation, persistence or a helper's retirement is unsuccessful/unknown, retain the first failure and explicit capability/helper obligations; no subsequent test or claimed cleanup success. Removing xattrs does **not** revoke capabilities held by an already running collector or prove target/BPF retirement. Retain any native owner under the unchanged close/hold rules; this finally step permits only metadata removal, not native-owner recovery.

**Gate:** One terminal invocation with original evidence and actual cleanup observations, or a retained non-invocation/failure. Account for both acquired helper CIDs, removal statuses and capability absence separately from native owners. No task may report complete with a running owned check; held native owners or failed/unknown capability/helper cleanup are blocking obligations.

### Task 3: Review the result and make the next integration decision

**Files:** Create `docs/internals/reviews/2026-10-23-hotspot-public-native-attempt.md`; append the reviewed native-lane result to `docs/internals/reviews/2026-10-23-hotspot-runtime-qualification.md`. Amend the native-status paragraph of `profiler/HOTSPOT_AGENTLESS.md` only to match an actual reviewed outcome. Read the existing release plan and issue528 for routing; do not edit N/S or maintenance code.

**Interfaces:**
- Consumes Task1 selections/grant and Task2 original logs/status/XML/scratch/owner evidence.
- Produces one of **BLOCKED_NOT_RUN**, **REJECTED_OR_INCOMPLETE**, or **QUALIFIED_PUBLIC_OBSERVED_CORPUS**, with separate owner/retirement and quality statuses. This is not schema2, all-control A-OBS, Kubescape-live or fullgate qualification.

- [ ] **Step 1: Review every acceptance predicate using actual originals.** Do not create another parser/framework or accept a green report without its originals. The unchanged `verifyObservedCorpus` and fixture supply the executable assertions; review these retained facts:

| Predicate | Required observation |
|---|---|
| Execution | Actual Gradle exit0; fresh suite has exactly1 test,0 skipped/failures/errors; correct selected source/init/assets/JVM/host |
| Ordinary target | Actual `runtime.txt`/maps/helper admission match pinned JVM, compiler/tiered/G1 and no agents/forced flags; selected non-root execution identity |
| Corpus |400 unique oracle tokens and matching400 records/terminals; four independently sourced platform workers; correct PID/birth/TIDs |
| Routes | At least360 useful correct leaf-before-route stacks, zero wrong/swapped route/TID/order, no truncated marked path; route denominators200 each, actual useful/unavailable counts reported |
| Compiled samples | Nonempty exact event-ID set with marked application frames, `scope_lookup=exact_pc|preceding_pc`, `scope_walk=returned` and no event error frame; report counts per route, not an all-route compilation claim |
| Accounting | Finalized, terminal accounting complete, physical drain complete and0pending occurrences; retained/discarded/unknown/loss qualifiers stated separately |
| Policy | Explicit policy-ineligibility/global incompleteness and refused policy conversion unchanged |
| Cleanup/custody | Actual session close/no pending owner plus checked target exit0; setup/cleanup helper identities, terminal exits/removals and host capability-absence check retained; no unknown owner, missing required evidence or unreported persistence failure |

If any predicate fails, preserve the first rejection and record **REJECTED_OR_INCOMPLETE**, even after a later approved cleanup succeeds. A skipped/unreached test stays **BLOCKED_NOT_RUN**. A failed invocation whose native execution cannot be established is reported explicitly at that level, not silently assigned a native exit code. An empty compiled set does not authorize warmup/GC/compiler changes.

- [ ] **Step 2: Obtain an independent outcome review without rerunning the test.** Hand over the exact request, original evidence paths/hashes, source snapshot, all failures and owners. Have the reviewer judge the public lane only. If a new source bug is found, record it in its existing owner/backlog or scaffold a scoped issue with `./scripts/adkw new-issue`; obtain the correction/retry scope before changing code or running again. Do not turn this into a repeated collector matrix.

- [ ] **Step 3: Write and validate the truthful handoff.** Record actual timestamps/status/exit, source/toolchain/JVM/asset/host identities, original XML/scratch hashes, marked/useful/unavailable/loss/compiled counts, original errno causes, each owner and first failure. Keep raw-transport and strict/lifetime/policy limitations explicit. On blocked inputs, publish only the exact missing decisions and no invented attempt. Verify new local references, then run documentation-only gates:

```bash
git diff --check
./scripts/adkw check-backlog
```

Use a new-file whitespace check for the new report as well; `git diff --no-index --check /dev/null docs/internals/reviews/2026-10-23-hotspot-public-native-attempt.md` may return1 solely because the files differ, so require no whitespace diagnostics. No compilation/full build is warranted by these report-only edits. Preserve native XML before any later separately approved build.

- [ ] **Step 4: Present the next decision, not another implementation batch.**

| Independent workstream | Concrete next authorized selection / exit |
|---|---|
| Storage release | Resume existing release plan Task1 submission step with the already reviewed current33-path package in its `private/task-8-artifacts/`; select owner/repository/target/base/action and explicit IgnoreRule/package scope. No new source review or publication by association. |
| Released consumers | Only after actual accepted remote `STORAGE_REVISION`: existing Task2 resolver Version/Sum/GoModSum/Origin, then `GOWORK=off` standalone builds and affected checks; downloads separately approved. |
| Kubescape live | Only after released graph and actual selected images/node/trust originals/security custody: existing Task5 current-server JSON/protobuf gate, then Task7 positive/recreation/required controls. This public-native result substitutes for none of them. |
| Full quality | Select issue528 maintenance separately, reproduce its actual current findings and fix owning tests without rule/baseline weakening; then obtain the complete quality/privileged-CI environment and authority. Do not mix its16historical errors with the one coordinator Minor. |

**Gate:** Independently reviewed public-native verdict with every owner accounted for, or a precise blocked/rejected result and required next decision. Whole-integration acceptance remains blocked by any missing release/live/control/full-quality gate.

## Plan self-review and execution handoff

| Spec / current requirement | Coverage |
|---|---|
| Native-first without storage dependency | Tasks1–3 only public consumer; release/live routes retained separately |
| Actual trust/source/runtime identity, no guessed asset or consent | Task1 selections, protected-byte check, passive scope, concrete approval; Task2 immediate digest/identity binding |
| Ordinary400/4/20, useful routes, sampled compiled application frames | Unchanged fixture plus Task3 exact predicate table; no workload/compiler edits |
| Complete available attempt evidence and failed-run retention | Task2 owned JUnit root/init script, original XML before interpretation, logs/exit and original scratch |
| Physical cleanup versus flags/schema success | Task2 actual hold/close/target outcomes and Task3 independent review; unknown owners block acceptance |
| Docker instead of host sudo, without containerizing the test | Task2 Step0/mandatory finally: cached immutable image, explicit local daemon, single-file bind, bounded helper capabilities, actual namespace/UID checks and acquired-CID-only cleanup |
| Existing A3 controls/reproducibility, B/C/D and strict gates | Not replaced: existing matched collector qualification needs actual provenance; this one corpus does not rerun/prove all controls or two reproducible bundles |
| No scope creep / quality waiver | No source changes; old suites reused within scope; issue528/fullgate remain separate; no automatic commits/worktrees or retry |

Planning validation checks local references, placeholder patterns and shell/Python snippet syntax without executing the snippets. The first backlog check identified the repository's mandatory YAML frontmatter; that metadata now precedes the requested Markdown plan header. Gradle init/Test-task API references were checked against official **Gradle9.6.1** documentation after Context7 reported quota exhaustion; JUnit temporary-directory behavior against official **JUnit5.10.2** guidance. No JDK/collector/Gradle/native/host-security probe, module download, fullgate or publication is performed by writing this plan.

Documentation sources: [Gradle9.6.1 init scripts](https://docs.gradle.org/9.6.1/userguide/init_scripts.html), [Gradle9.6.1 Test.systemProperty](https://docs.gradle.org/9.6.1/dsl/org.gradle.api.tasks.testing.Test.html), [JUnit5.10.2 temporary directories](https://docs.junit.org/5.10.2/user-guide/#writing-tests-built-in-extensions-TempDirectory). Docker amendment checked against current official, unversioned [create](https://docs.docker.com/reference/cli/docker/container/create/), [start](https://docs.docker.com/reference/cli/docker/container/start/), [wait](https://docs.docker.com/reference/cli/docker/container/wait/), [remove](https://docs.docker.com/reference/cli/docker/container/rm/), [capabilities](https://docs.docker.com/engine/containers/run/) and [userns-remap](https://docs.docker.com/engine/security/userns-remap/) documentation, plus [setcap(8)](https://man7.org/linux/man-pages/man8/setcap.8.html), after Context7 quota exhaustion. Actual installed Docker/helper-tool versions remain execution-time observations, not facts certified by documentation.

Inline execution and input preparation already occurred; the current request is only to amend this plan. Retain all three workspaces and completed checks. A later explicit selection of the amended Docker execution scope resumes at the remaining helper image/scope binding and Task2 permission bracket, not sudo authentication or repeated discovery. The one native attempt remains unused; no helper or native execution is authorized merely by saving this amendment.
