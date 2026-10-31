# Present mazewall through executable JUnit examples

## Lead with one useful application

Choose **one** independent story for the audience:

1. **Koog document agent:** [guide](../../demos/koog-document-agent/README.md).
   Start with an incident bundle: a real AIAgent reads actual evidence and returns
   a model-generated hypothesis, citations and next steps. `triageIncident` requires
   an explicit provider/model; hosted OpenAI avoids local inference load, while
   Ollama is optional and must already be prepared. Neither is started/downloaded
   by the demo. Select only synthetic data approved for provider disclosure.
   Without live inference, the named `IncidentAgentTest`/`IncidentBoundaryTest`
   runs use an explicitly labelled fixture executor with the real graph and native
   readers. Show ordinary synthetic-canary access, actual protected refusal, useful
   work and physical retirement—not a model-injection-resistance claim.
   Run `outlineFile` on an actual Markdown file in a separate JVM for useful output
   from the opaque generated worker. Host file acquisition is outside parser
   containment. Legacy `demoThread`/`runPortal` remain offline engineering examples;
   `demoPortal` adds a separate test-only denied-read/crash fixture. Never describe
   that controlled fault worker as an exploit in the parser.
2. **Spring report API:** [guide](../../demos/spring-report-api/README.md).
   Start with its `presentationTest` tour and actual operational-report body,
   request IDs, transaction outcomes and observed audit effects. For normal service,
   prepare the exact finished deployment and separate trusted review; `serve`
   never approves itself. Show401 before body, approver200/commit, real denied
   read/rollback, reader403/post-authorization rollback, then approver recovery.
   Show request-correlated outcomes, readiness refusal on `drain` and actual close
   on `stop`. No filesystem report writes or Koog dependency are involved.

Change the Koog input bundle or the Spring template, then inspect the actual changed
output. Keep them independent: no combined app or Koog/model dependency in Spring.
For policy maintenance, use the Koog guide's measured reader → coverage gate →
informational diff → independent review → fresh enforcement recipe. Its named file
profile is not a trace of the whole agent; Spring's deployment candidate is not a
syscall profile. Neither flow auto-updates grants/review or retries refused work.

The guides include ordinary distributions/source kits and synthetic inputs. Do not publish or replace an
operator installation as part of rehearsal. Remote-byte identification and the
actual-human rehearsal remain separate gates. The [optional global benchmark job](../../benchmarks/integration-boundaries/README.md#optional-global-benchmark-job)
provides boundary-harness observations, not application/inference timing or a required
rehearsal gate. A fixture model proves adapter
execution, not live reasoning, citation truth, model resistance or safe provider
disclosure. The unchanged ten-second deadline may reject slow inference; live
quality/timing requires a separate operator-approved smoke test. The newer
Spring record-result library extension is optional; the headline HTTP path stays String.

## Start with the runnable application paths

For a first evaluation, choose one independent example rather than building a
combined Koog/Spring application:

The core CLI source build needs JDK25/Linux/native permissions and Clang with its
BPF backend because the CLI module also builds profiler resources. Check the
[compiler probe or configured development-image route](../../demos/cli-demo/README.md#start-here-an-owned-workspace-read)
before a clean first run. No profiling permission is needed for the workspace read.

- Core: `./gradlew :demos:cli-demo:runWorkspaceExample --console=plain`.
  [Policy, owned call and expected observations](../../demos/cli-demo/README.md#start-here-an-owned-workspace-read).
- Koog: the [thread reader and generated Portal commands](../../demos/koog-contained-tool/README.md)
  use the existing producer wrapper and run in separate JVMs. Their incompatible
  process baselines must not be combined. These are real Tool examples, not an
  `AIAgent` loop or a model-selection demonstration.
  Separate [published agent qualification](../../tests/consumers/koog-portal/README.md#real-agent-loops-without-a-model-account)
  runs actual offline scripted loops, denied/allowed sequences and queued-agent
  cancellation. Those engineering checks are not an unfamiliar-human rehearsal.
- Spring: the [standalone quickstart](../../demos/spring-boot-sandbox/README.md#start-here-ordinary-report-generation)
  stages explicit local publications, exercises normal `measure-report`/`generate`
  and retains separate `measure`/`verify` negative scenarios. The JUnit harness is
  external authority for private known fixtures only, not production approval.

The primary source files are `WorkspaceExample.kt`, `ThreadReadTool.kt` /
`TextTools.kt`, and `ReportService.java`. Each owning guide separates that ordinary
application code from bootstrap, approval and verification scaffolding and gives
one guided modification. Native support failures remain fatal, not skipped.

## The story (18 minutes plus questions)

1. **Unrestricted behavior (1 minute):** synthetic untrusted input launches a
   process and creates an acquired marker. Show the actual assertion, not an exploit claim.
2. **Syscall and path contracts (3 minutes):** ordinary calculation still works;
   a previously reachable socket is rejected; the declared workspace can be read,
   its readable sibling cannot. Explain Seccomp-BPF versus Landlock.
3. **Discovery (3 minutes):** observe the real named file operation, coverage and
   candidate DSL; a content change proves the protected call rereads it. Missing
   coverage refuses conversion. A profile is not permission for unseen future paths.
4. **Owned execution (3 minutes):** virtual caller → platform worker; declared
   context capture/restoration; finite admission rejects with zero body entry;
   cancelled caller while held cleanup keeps drain Incomplete, then physical end.
5. **Supervised tools (3 minutes):** acquired local HTTP comparison, private-address
   custom CONNECT rejection, authorized analysis vs denied web-stack spawn. This is
   experimental exact-call-path supervision with early VFORK, not shared-heap isolation.
6. **Process Portal (2 minutes):** typed ASCII transformation preserves Unicode;
    actual distinct child/readiness, opaque guest classpath, bounded arguments and joined close;
    immutable diagnostic samples keep delivery and canonical retirement separate.
7. **Framework adoption (3 minutes):** finished Boot report and H2 effects, external
   known-fixture review, transactions/denials/physical end; separately named ordinary-JAR
   optional Security principal/rollback/restoration. Never attribute Security to the Security-free Boot fixture.

Finish with one limitation sentence: **owned thread contracts restrict direct OS
behavior; they do not isolate shared JVM memory or arbitrary hostile Java code.**
Process Portal proof is a bounded first-party service contract. Production review,
launch authority and compatible already-enabled CET remain their own gates.

## Run and inspect

Start from the assigned source checkout with JDK25. Prepare normal builds and native
prerequisites beforehand; use the existing shared verification lock for native/build
commands. A demo is not ready if the selected required tests are skipped or fail.

```bash
./gradlew presentationTest --console=plain --no-configuration-cache
./gradlew :demos:cli-demo:presentationTest --tests '*EnforcementPresentationTest' --console=plain
./gradlew :demos:cli-demo:presentationTest --tests '*ProfilePresentationTest' --console=plain
./gradlew :demos:cli-demo:presentationTest --tests '*OwnedRuntimePresentationTest' --console=plain
./gradlew :demos:agent-sandbox-demo:presentationTest --console=plain
```

The owning tasks select `@Tag("presentation")`, execute fresh (no cached/up-to-date
test result), print successful observations and retain normal XML/HTML reports under
each module's `build/test-results/presentationTest` and `build/reports/tests/presentationTest`.
In an IDE run the individual `@Test` or class; fresh child fixture ownership still applies.
Display names/order are narrative only. Every test also runs independently.

Koog's already-existing two-stage producer bootstrap is required; do not bypass its
source/JAR receipt validation:

```bash
bash scripts/koog-build.sh --console=plain --no-build-cache -- \
  :demos:koog-contained-tool:presentationTest :demos:koog-contained-tool:portalCheck
```

For current published-only examples, prepare a fresh current candidate. The complete
source-bound gate below stages one itself and runs actual Koog, ordinary-JAR
Security/JDBC and finished-Boot showcases plus ownership/negative controls. It owns
its lock internally:

```bash
./gradlew optionalIntegrationCheck --console=plain --no-configuration-cache
# Or supply the exact current repository/version from the manual consumer recipe:
: "${repository:?Set the absolute current publication repository}"
: "${version:?Set its exact producer version}"
./gradlew -p demos/spring-boot-sandbox presentationTest \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" --console=plain --no-configuration-cache
./gradlew -p tests/consumers/spring-jdbc presentationTest -PuseStarter=true \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" --console=plain --no-configuration-cache
./gradlew :demos:vulnerable-web-app:presentationTest --console=plain
```

The Spring parent JUnit fixture acquires private resources, independently approves
its fixed measured candidate, then executes the actual finished-JAR native body.
Tests assert checked effects, real diagnostic lifecycle, actual file bytes and artifact identity. It is
external **test-fixture** authority, not application self-approval or a production reviewer.
The web tests reuse the real bean/HTTP assertions. Its prepared-policy control and
real profiling publication/incomplete refusal are separate owning proofs. Run the
current profile-first native tests for that pipeline; the historical collector-drain
and unresolved-CONNECT defects are not current completion evidence. The core tour does not start Compose or
`./scripts/run_vulnerable_app_demo.sh`.

After preparation, add `--offline` for rehearsal. The Koog receipt still needs its
normal bootstrap if producer inputs changed. No live model, public network endpoint,
Compose setup or extra shell runner is required for the core sequence.

## Interpret the outputs honestly

- `INFERRED_PERMISSION_FAILURE` says the controlled JDK operation failed with
  permission evidence; it is not authenticated syscall provenance.
- The supervised agent fixtures require actual `OBSERVED_POLICY_DENIAL` from their
  unchanged handlers. Timeout/unreachable server is not a successful block.
- Process-wide restrictions stay permanent until that fresh JVM exits. Closing a
  runtime retires owned workers; it does not remove filters.
- SQL rollback does not undo file/network/heap effects. Virtual caller completion
  does not establish body/cleanup completion.
- Root aggregation names its included scope. Optional standalone packaging/Security,
  cgroups/native assets and hardware features are never implied by a core green run.

## Rehearsal record

Automated assertions retain ordinary Gradle JUnit XML/HTML reports and owning
backlog/PR source-bound qualification. These are not a human usability rehearsal.
Use the existing [unfamiliar-developer rehearsal item](../internals/backlog/issue-20261013-005319-256-rehearse-the-complete-feature-tour-with-an-unfamil.md):
have an operator-selected actual developer follow the independent quickstarts and
guided changes without undocumented help. Record setup time, confusion, which
files they could adapt, allowed/denied/cleanup observations and live-show timing
separately. Rehearse two fresh offline runs on the presentation host. Human
timing/comprehension, recordings and production/CET approvals remain open until
genuinely observed; no agent result or generated participant replaces them.

For the integration slice, use separate Koog and Spring applications. Identify
the actual released artifacts before starting: record resolved versions and JAR
checksums, not only a mutable SNAPSHOT label. Repository credentials remain
operator configuration; an unavailable publication is a blocker, not permission
to substitute a sibling source build. Preparation/download time is recorded
separately from first use and guided modification. After preparation, perform
two fresh offline JVM runs and verify allowed/denied/cleanup observations without
undocumented help or widened permissions. These integration observations alone
do not close the remaining full-tour, recording or hardware acceptance criteria.
