---
title: "Cancel live incident HTTP requests at the agent deadline"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/koog-document-agent/src/main/kotlin/demo/koog/IncidentApplication.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Cancel live incident HTTP requests at the agent deadline

**Context:**
PR562 review finding1: actual JDK HTTP request outlives the ten-second Kotlin agent budget; a13-second loopback stall took14.2seconds. Operator approved replacing the JDK adapter with official Koog Ktor adapter; no model downloads/startup/provider calls, permission changes, retries or timeout increases.

**Needed:**
1. Add real packaged-client stalled HTTP regression RED; replace adapter/update locks and checksums; require cancellation before releasing server, keep original timeout and provider close semantics; focused suites, exact acceptance and full build.

## Outcome

The explicitly approved official Ktor adapter replaces the blocking JDK adapter;
existing Ktor/engine versions, provider-close semantics and10-second agent budget
are unchanged. No custom transport, retry or fallback was added.

The actual packaged-client regression requires TimeoutCancellationException while
its acquired loopback HTTP server is still held. Construction tests make no model
requests; no model/server or real credentials are needed. [Current qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md)
includes Koog26host/11native and preserved published/cache/retirement checks.
Open pending managed landing; fixture transport cancellation does not qualify live
model behavior, hardware latency, disclosure safety or external CI cancellation delivery.

---
<!-- id: issue-20261030-144907-661-cancel-live-incident-http-requests-at-the-agent-de  file: issue-20261030-144907-661-cancel-live-incident-http-requests-at-the-agent-de.md -->
