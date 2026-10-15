---
title: "Repair owned profile-first vulnerable web demo startup"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "demos"
target_modules: ["demos:vulnerable-web-app"]
target_files:
  - "scripts/run_vulnerable_app_demo.sh"
  - "demos/vulnerable-web-app/compose.yml"
  - "demos/vulnerable-web-app/Containerfile"
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/DeploymentContractTest.kt"
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/VulnerableWebAppFreshJvmTest.kt"
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/VulnAppApplicationTests.kt"
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/WebPresentationTest.kt"
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/MultipartStorageConfig.kt"
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/MazewallConfig.kt"
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/WebResponseConfig.kt"
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/ContainmentHttpAdvice.kt"
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/config/MultipartStorageConfigTest.kt"
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/config/ContainmentHttpAdviceTest.kt"
  - "exploits/test_web_demo.py"
  - "demos/vulnerable-web-app/README.md"
  - "docs/REPRODUCING_RESULTS.md"
  - "docs/presentation/PRESENTER_GUIDE.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Repair owned profile-first vulnerable web demo startup

**Context:**
Inspection: compose up launches profile/protected together although enforcement requires completed profile publication; readiness loops70-80 have no deadline and curl without --fail can accept HTTP errors. Reused latest images are not bound to the rebuilt JAR; default-project teardown and suppressed cleanup failures obscure resource ownership.

**Needed:**
1. Use a fresh owned Compose project, bind actual image/JAR identities, exercise profile first and stop it orderly before protected startup; poll declared health endpoints under finite deadlines, preserve logs and initial failure, and verify teardown of only acquired services/volumes.

**Presentation path:** Extend the existing VulnerableWebAppFreshJvmTest with readable tagged feature cases and real profile-first lifecycle checks. Repair the existing script only as legacy guidance/reproduction work; do not add another launcher, shell test framework or Compose dependency to the core JUnit talk. Optional real container proof remains separately labeled.

**Acceptance:** An empty-profile cold run reaches profile exercise, validated complete publication, then protected readiness in that order. Missing/incomplete profile and a stale image fail before a protected verdict. HTTP errors never satisfy readiness; all waits terminate. Success, scenario failure and interruption attempt exact owned cleanup and report incomplete retirement honestly. Existing projects/volumes and unrelated resources are preserved.

**Continuation plan:** Planning confirmed the current JUnit tests cover prepared-policy enforcement and real publication/incomplete refusal separately; a genuine complete-profile-to-enforcement case is still required. Docker 29.9.0 and Compose 5.6.0 are installed, but the configured Podman socket is absent. Restore the intended runtime before claiming actual container acceptance.

**Task 4 evidence (still open):** Script commits `59c0f36d` through `a7efc590` own a fresh project, bind image/JAR identity, and gate protected start on validated generation evidence. The genuine complete profile→enforcement JVM case remains blocked: collector[1] drain did not complete; collector[2] CONNECT was observed without a parsed destination. Published instructions keep the narrower proofs and do not call that case a passing sweep. No live container acceptance is claimed. Do not archive.

**2026-10-15 operator-approved continuation:** Core profiler issue172121-857 now
has actual complete native publication and network evidence. Real container run
`run-2489629c7a3a` reaches generation validation, then correctly refuses a stale
Tomcat multipart scratch path. Multipart staging must be a stable subdirectory of
the declared uploads resource, preserving existing Spring admission limits and
rejecting symlink/outside-root configuration. A real servlet configuration
regression fails before repair (`run-a021f4e94007`).

The consumer also treated a policy-learning base as an approval ceiling. A
corrected fresh-connection regression proves an unclamped learned policy can
serve a prohibited owned canary (`run-4174d846faad`); restrictive composition denies
that actual request with permission evidence (`run-27cb999a8e7f`). Reapply the
explicit ceiling after learning and make per-service filesystem authority
explicit. Existing shared descriptors are not revoked by thread-scoped filters:
the positive control closes HTTP keepalive so it tests a genuinely new connection.
Shared parser issue182018-091 handles redundant learned children under approved
base roots without ignoring missing-resource failures. No full live sweep yet.

Real run `run-6e1bb418da43` now reaches protected readiness: benign admin/upload
pass and the actual escape write is denied. The report correctly remains
inconclusive: typed denial was lost as generic HTTP500, packaged SQL JSON chose
an optional transitive Kotlin serializer without its reflection support, and
the direct SSRF control invoked an uninstalled `mazewall-fetch` helper. Keep
those failures distinct; use installed Jackson for standard MVC JSON, preserve
denial evidence at the HTTP boundary without promoting inferred/unknown evidence,
and repair the actual installed-curl control under issue005317-374. No new
runtime dependency or permission fallback is needed.

## Resolution

Actual fresh-project/empty-volume run `full-safety-profile-02` passes the complete
standard five-row comparison (`run-c5e6ca7b42bf`): orderly profiling stop and
complete generation validation precede protected readiness; benign controls pass,
unprotected unsafe effects occur, protected escape/fetch refusals remain explicitly
inferred, and SQL disclosure remains an application-level limitation. Exact
image/JAR identities and owned resource disappearance are verified by the runner.
Raw receipts remain local; the historical isolated results report is untouched.

The complete demo host suite passes (`run-a959b59a52d9`) including real fresh-connect
ceiling and HTTP denied-upload/continued-benign controls. All actual application
fixtures supply their own roots; no production `/app` fallback was introduced.
The full included-module build passes (`run-bbf486925536`), all mutation gates
pass (`run-c0f65b467853`), and all62 report/ownership tests pass
(`run-05f4716ff572`). Connected review preserves strict prepared-resource checks,
existing upload limits, process baseline, evidence strength, and shared-FD/thread
scope limitations. No new dependency, API break, gate removal, threshold change
or benchmark publication. Remote exact-head CI remains required before merge.

---
<!-- id: issue-20261013-005317-622-repair-owned-profile-first-vulnerable-web-demo-sta  file: issue-20261013-005317-622-repair-owned-profile-first-vulnerable-web-demo-sta.md -->
