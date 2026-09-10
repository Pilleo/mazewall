---
title: "Make CLI and agent containment claims verifiable"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "demos"
target_modules: [demos/cli-demo, demos/agent-sandbox-demo]
target_files:
  - "demos/cli-demo/src/main/kotlin/demo/ProfileAndEnforceDemo.kt"
  - "demos/cli-demo/src/main/kotlin/demo/DemoApp.kt"
  - "demos/agent-sandbox-demo/src/main/kotlin/io/mazewall/demo/agent/AgentOutcome.kt"
  - "demos/agent-sandbox-demo/src/main/kotlin/io/mazewall/demo/agent/Tools.kt"
target_symbols:
  - "runProfileAndEnforce"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make CLI and agent containment claims verifiable

**Context:**
Review-hardening task 9: profiling must be explicit, rerun real profiled I/O under strict compiled policy, require exact authorization frames and authenticated denial evidence, and preserve fatal errors and owned fixtures.

**Needed:**
1. Add focused and isolated-JVM regression controls, correct both demonstrations, and record executable evidence.

**Resolution and evidence (2026-09-10):**
- Default `all`/`both` excludes profiling; explicit profile mode refuses incomplete evidence and reruns the exact named file read under its compiled policy, with changed file content proving a new read. All files stay under an owned private fixture.
- CLI and agent authorization use exact declaring class and method names. Real network/exec controls and sealed outcome summaries never count inferred permission text as authenticated denial. Fatal errors retain identity across executor/reflection wrappers and cleanup.
- `ProfileAndEnforceDemoTest` covers strict completeness, reread, exact frames, and two real isolated-JVM profiles. `DemoAppTest` covers explicit dispatch, infrastructure exit, and a real isolated loopback NO_NETWORK control. `DemoOutcomeTest` covers outcomes, wrapped fatal errors, and no-follow fixture cleanup.
- `AgentDemoOutcomeTest` covers authenticated/inferred evidence, exact frames, cleanup/fatal identity, and owned fixtures. `AgentScenarioFreshJvmTest` executes five isolated positive/negative scenarios. Four existing `NetworkAddressClassifierTest` tables cover IPv4/IPv6 boundaries, mapped addresses, malformed and unsupported data.
- Fresh command: `./gradlew --no-daemon --no-build-cache :demos:cli-demo:cleanTest :demos:agent-sandbox-demo:cleanTest :demos:cli-demo:test :demos:agent-sandbox-demo:test`. Passed 29 CLI + 18 agent tests, zero failures/skips, 55 tasks (4 executed, 51 up-to-date), 24 seconds. Test XML timestamps: 2026-09-10T21:29Z.
- The stronger positive control exposed and required the separate ptracer correction `ac79af08`; the demo regression was not weakened. Both demonstration READMEs are updated. Demo ktlint/Detekt tasks are intentionally unavailable in existing build configuration; no static-analysis pass is claimed.

---
<!-- id: issue-20260910-212626-611-make-cli-and-agent-containment-claims-verifiable  file: issue-20260910-212626-611-make-cli-and-agent-containment-claims-verifiable.md -->
