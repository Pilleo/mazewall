---
title: "Repair owned profile-first vulnerable web demo startup"
severity: "HIGH"
status: "open"
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
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/WebPresentationTest.kt"
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

---
<!-- id: issue-20261013-005317-622-repair-owned-profile-first-vulnerable-web-demo-sta  file: issue-20261013-005317-622-repair-owned-profile-first-vulnerable-web-demo-sta.md -->
