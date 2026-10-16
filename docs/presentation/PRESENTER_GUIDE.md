# Present mazewall through executable JUnit examples

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

Automated assertions and native runs are recorded in the implementation ledger at
`.superpowers/sdd/2026-10-13-junit-presentation/progress.md`. Full completion checks
must be recorded there before calling the implementation verified. Rehearse two
fresh offline runs on the presentation host, then have an actual unfamiliar developer
follow this guide. Human timing/comprehension, recordings and production/CET approvals
remain open until genuinely observed; no agent result replaces them.
