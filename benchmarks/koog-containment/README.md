# Koog/Portal containment contract demonstration

This deterministic suite checks **supported tool execution has explicit, tested
boundaries**. It is inspired by [AISI's article](https://www.aisi.gov.uk/blog/can-ai-agents-escape-their-sandboxes-a-benchmark-for-safely-measuring-container-breakout-capabilities)
and [SandboxEscapeBench](https://github.com/UKGovernmentBEIS/sandbox_escape_bench),
not a run, port or score of its 18 container-escape scenarios. This recipe is not
a measured result or security certification; a passing run is evidence only for
the named contracts, exact artifacts and environment exercised.

The [published-JAR consumer](../../tests/consumers/koog-portal/README.md) uses
Koog 1.3.0 and the [Portal adapter](../../integrations/koog/README.md). The sole
advertised production tool remains `uppercase_ascii`, a bounded first-party
ASCII transformer with zero capabilities. Dangerous operations exist only in
the `koogNativeFixtures=true` service and are bound through test-only adapters,
never the model's production registry. They attempt real operations and do not
manufacture permission exceptions.

## Scenario and coverage matrix

All controls run outside the protected worker. Files contain only the synthetic
`KOOG_SYNTHETIC_CANARY` in an owned temporary directory; networking uses only an
owned loopback listener, and process launch uses the current JDK, without a shell.

| Check | Positive control | Required protected outcome |
|---|---|---|
| Read | Host reads the newly created canary file. | Completed remote `java.nio.file.AccessDeniedException`; no canary in the remote diagnostic. |
| Write | Host writes and restores that file. | The same typed denial; original canary bytes remain unchanged after the attempt. |
| Symlink read | Host reads the same file through a new symlink. | The same typed denial through the alias; not a race-proof path-handling claim. |
| Loopback connection | Host connects to, and is accepted by, its live `127.0.0.1` listener. | Completed remote `java.net.SocketException` with exactly `Operation not permitted` or `Permission denied`, not refusal or timeout. |
| Process launch | Host launches the current JDK's `java -version`, waits for exit 0 and reaps it. | Completed remote `java.io.IOException` with explicit `error=1` or `error=13`, not a missing executable or generic I/O error. |

Connects and listener acceptance have 2,000 ms bounds. Every subprocess acquired
by the process-launch check has a 5-second execution bound and a separate 5-second
kill/reap bound. Its fixed small version output is discarded through an owned
pipe, not a `/dev/null` redirect that could fail before spawn. Cleanup failure
fails the check. The trusted native launcher sets only `LC_ALL=C` for reproducible
network diagnostics; this is not a library-wide environment guarantee.

Existing checks are retained, not replaced by these five attempts:

| Contract | Coverage |
|---|---|
| Registry and typed dispatch | Actual upstream registry, only `uppercase_ascii`, generated typed service, strict JSON and selector-looking text treated as data. |
| Bounds | 65,536-byte UTF-8 text input/result limits and 131,072-byte request/response limits. |
| Required readiness | Genuine generation-bound process-wide Landlock/Seccomp installation evidence; Landlock precedes Seccomp. READY and immutable diagnostic snapshots are checked before submission. |
| No replay/state loss | Crash and wire failure are terminal for the stateful group; original owner/PID, rejection of later calls and independent-group functionality remain checked. The owning broker regression also checks lifetime spawn accounting. |
| Lifecycle | Coroutine cancellation, joined runtime close, CLOSED/retirement-complete diagnostics and no live children. Actual acquired `ProcessHandle`s from every opened generation must be dead after close; a result or cancellation alone is insufficient. |
| Adapter and packaging | Owning adapter tests retain validation-before-submission, cancellation ownership and no retry after ambiguous failure. Existing package checks separate production/test fixtures and opaque worker JARs from host Koog/Spring classes. |

Ordinary uppercase calls must still work after the filesystem/network/process
denials and in an independent group after the deliberate crash. The worker's
required baseline, Portal startup/call/close budgets and production registry are
unchanged.

## Supported reproduction environment

Run only in an **explicitly disposable supported Linux environment**, never on a
long-lived runner holding production authority. Use JDK 25 for this integration;
core compatibility remains JDK 22+. The kernel must provide Landlock TSYNC
(ABI 8+) and process-wide Seccomp, and the outer runtime must permit their actual
installation. An older kernel or restrictive outer policy cannot be worked
around with a thread-only baseline, in-process fallback or skip.

The independent consumer uses stable Kotlin 2.4.20, coroutines 1.10.2 and host
serialization 1.10.0; the independent worker closure uses serialization 1.9.0.
Use the existing configured Podman/Docker harness for aggregate acceptance; see
the [testing strategy](../../docs/internals/designs/core/testing-strategy.md#build-and-evidence-gates).
Do not install alternate tooling or downgrade the kernel to run upstream exploits.

### Required committed aggregate acceptance

From the repository root, after committing the reviewed producer inputs:

```bash
./scripts/run_containerized_tests.sh :optionalIntegrationCheck --no-configuration-cache --max-workers=1
```

This existing gate owns its verification lock: **do not wrap it in another
`flock`**. It stages exact publications in an owned disposable clone and runs
real Koog, JDBC/Security and finished-Boot consumers. The default core build
excludes Koog and cannot substitute for this gate.

### Focused Koog-only staging

This is the existing [local staging recipe](../../tests/consumers/koog-portal/README.md#declared-source-workflow-and-local-staging),
with a newly acquired candidate/repository for each run. Run from the repository
root in the disposable environment. Never republish changed fixture bytes over
an accepted coordinate or rewrite historical locks.

```bash
root="$PWD"
lock="$(git rev-parse --path-format=absolute --git-common-dir)/mazewall/verification.lock"
mkdir -p "$root/build" "$(dirname "$lock")"
revision=$(git rev-parse HEAD)
candidate=$(mktemp -d "$root/build/koog-containment.XXXXXXXX")
repository="$candidate/repository"
mkdir "$repository"
version="0.1.0-integration.${revision:0:12}.${candidate##*.}"

flock "$lock" bash scripts/koog-build.sh -I "$root/tests/consumers/stage.init.gradle" \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" -- \
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

flock "$lock" ./gradlew -p "$root/tests/consumers/koog-portal" \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  --write-locks verifyOperationRoot stageFreshProbe verifyPublishedClosure --no-configuration-cache

flock "$lock" ./gradlew -p "$root/tests/consumers/koog-portal" \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  verifyFreshNative --no-configuration-cache
```

Generate only the new `locks/<consumerVersion>/gradle.lockfile` namespace once;
the final command uses strict locks, staged published JARs and a fresh acquired
working directory/worker cache. Native execution is not cached. Retain the source
revision, any uncommitted fixture diff, candidate, argv, logs and exits. A focused
run is preliminary fixture evidence, not committed aggregate acceptance.

## Expected evidence and failure interpretation

Successful native execution exits zero and retains `KOOG_NATIVE_FRESH_OK`.
Only after every old/new assertion and acquired-worker retirement check does it
emit exactly one additional line:

```text
KOOG_CONTAINMENT_V1_OK read=denied write=denied symlink=denied network=denied process=denied controls=pass retirement=pass
```

The parent acceptance test still requires zero exit and the old marker. It also
requires exactly one line beginning `KOOG_CONTAINMENT_V1_` and exact equality to
the line above; missing, duplicate, partial or contradictory summaries fail.
Host-safe corruption regressions exercise this gate:

```bash
./gradlew consumerHarnessTest --tests '*KoogContainmentSummaryTest*' --console=plain
```

The accepted summary is printed into parent JUnit output at
`build/test-results/optionalIntegrationCheck/TEST-io.mazewall.verification.OptionalIntegrationAcceptanceTest.xml`.
CI already uploads it in **`optional-integration-junit`** alongside adapter JUnit
diagnostics; no separate benchmark report/workflow is needed.

A missing native prerequisite is unavailable evidence, not protection success.
A failed positive control, unexpected remote type/diagnostic, timeout, unknown
outcome, changed bytes or incomplete cleanup fails the check, never a passing
skip. A summary copied into a log without actual successful native execution is
not evidence. Permission-looking remote errors plus controls are bounded
behavioral evidence, not authenticated attribution to a particular LSM or
Seccomp rule.

## Limits and non-goals

- This is controlled adversarial **test code**, not arbitrary AI-agent containment
  or proof that the production transformer accepts dangerous operations. Host
  mappings, model JSON parsing and codecs remain outside worker protection; the
  entire Koog agent is not sandboxed.
- Worker, host and container share a kernel. These checks do not establish
  resistance to kernel exploits, hostile host code or a compromised broker, and
  unsuccessful attempts are not a security proof.
- No Inspect/Python dependency, upstream exploit scripts, old kernels, privileged
  escape challenges, Docker-socket exposure or AISI success-rate comparison.
- No model account, inference budget, nondeterministic PR gate or leaderboard.
- No new cgroup/OOM, inherited-FD, arbitrary-environment or credential-isolation
  guarantee. The default Koog resource profile is not evidence of hard CPU/RAM
  enforcement. No real secrets or management sockets are probed.
- No shell/MCP/generated-code runtime, broker capability expansion, unrestricted
  worker mode, permission selector, automatic retry/replay or in-process fallback.

Any future live-agent/CVE campaign requires **separate approval and a hardened
outer VM independent of the mazewall/container boundary under test**, with
synthetic data, restricted connectivity, finite model/time budgets, independent
observations and disposable disks. Several upstream old-kernel exploit targets
are incompatible with the required Landlock TSYNC baseline. Provisioning or
running that campaign is outside this demonstration; findings should become
deterministic regressions.
