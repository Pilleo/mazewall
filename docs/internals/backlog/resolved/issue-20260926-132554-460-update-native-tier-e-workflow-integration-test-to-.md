---
title: "Update native Tier E workflow integration test to Unix control socket"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEDaemonNativeWorkflowIntegrationTest.kt"
target_symbols:
  - "TierEDaemonNativeWorkflowIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Update native Tier E workflow integration test to Unix control socket

**Context:**
Clean rootful integrationTestFreshJvm fails because this fixture parses a TCP listening port while TierEKotlinDaemon now announces a UNIX socket and AgentWorkload connects with UnixDomainSocketAddress.

**Needed:**
1. Create an owned per-test UNIX socket path, pass it through TIER_E_SOCKET to the daemon and AgentWorkload, and verify the same workflow and scoped cleanup on the rootful gate.

**Resolution:** The fixture now creates an owned socket under its temporary
definition root, passes the exact `TIER_E_SOCKET` path to daemon and workload,
and cleans up both after the run. The clean rootful gate advanced through the
fresh-JVM test and rejected only at the separate native phase gate.

---
<!-- id: issue-20260926-132554-460-update-native-tier-e-workflow-integration-test-to-  file: issue-20260926-132554-460-update-native-tier-e-workflow-integration-test-to-.md -->
