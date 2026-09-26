---
title: "Qualify real Tier E guest-host PID reuse against a rootful collector"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler-native/tests/AgentWorkload.java"
  - "scripts/probe_tier_e_real_pid_reuse.py"
  - "scripts/tests/test_probe_tier_e_real_pid_reuse.py"
  - "scripts/run_tier_e_split_privilege_fixture.sh"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/controller/TierESplitFixtureController.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERealPidReuseProbe.kt"
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierELegacyFixtureLaunch.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierESplitFixtureControllerTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierELegacyFixtureLaunchTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERealPidReuseProbeTest.kt"
  - "config/testing/tier-e/real-pid-reuse.json"
  - "docs/internals/designs/profiler/tier-e-testing-migration.md"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
  - "docs/internals/evidence/tier-e-source-lifecycle-20260923/README.md"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/.gitignore"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/README.md"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/SHA256SUMS"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/collector.log"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/control.log"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/marker-version-proof.txt"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/probe.log"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/real-pid-reuse-probe.json"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/target.log"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/terminal-report.json"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/unrelated-user.log"
  - "docs/internals/evidence/tier-e-real-host-pid-reuse-20260924/verdict.env"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify real Tier E guest-host PID reuse against a rootful collector

**Context:**
Existing simulated-incarnation fixture did not establish that the registered rootful collector rejects a genuinely recycled guest-host PID. A disposable VM run must hold a replacement with the same PID and a different /proc starttime while the collector observes it; keep hybrid evidence policy-ineligible.

**Kotlin guest preflight finding (2026-09-26):** The disposable VM showed
`pid_max=4096` to the shell while JDK 25 `Files.readString` returned only `4`
for the procfs sysctl file. A buffered line reader returned `4096`. The
fixture and probe now require a complete bounded numeric line for `pid_max`
and `ns_last_pid`; the first Kotlin attempt was rejected before any containers
were created and the guest PID limit was restored in its exit trap.

**Needed:**
1. Retain a bounded registered-host-PID probe, rootful v2 recovery fixture, negative terminal report, raw logs, guest-only pid_max isolation, checksums, and design/backlog references.
2. Match the registered PID and starttime against the marker proof and the real replacement's live `/proc` starttime. Require `PROCESS_IDENTITY_REUSED`, incomplete lifecycle, zero resolved syscalls, and `policy_eligibility=false`.
3. Restore the guest's original PID limit, verify `SHA256SUMS`, the Python probe test, native fixture compilation, and `./gradlew build`. Keep unpaused-race frequency and hybrid promotion outside this claim.

---
<!-- id: issue-20260923-235327-744-qualify-real-tier-e-guest-host-pid-reuse-against-a  file: issue-20260923-235327-744-qualify-real-tier-e-guest-host-pid-reuse-against-a.md -->
