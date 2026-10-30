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

## Execution

Base `2c2dc791af8c6b691bb5235f0ceec5da9a868783`; user approved all four PR review
fixes and separately approved replacing JDK adapter with official Koog Ktor adapter.
No custom HTTP layer, worktree, separate plan/journal, new model or changed budget.
- [x] Actual packaged-client held-server deadline test RED→GREEN before release.
- [x] Locked adapter replacement; all existing Ktor/engine versions unchanged.
- [ ] Final focused/CI-style checks, committed-source acceptance and full build.
- [ ] Commit and update existing PR562; no merge or installation replacement.

`LiveClientClasspathTest` first failed because the real JDK HTTP call remained
blocked after the agent timer fired; the same test now requires the real transport
to return TimeoutCancellationException while the HTTP server is still held. The
server uses acquired127.0.0.1:0 and never runs inference or reads credentials.
The normal client-construction proof remains. Agent budget stays10seconds; owned
test setup/observation bounds are separate and no previous timeout was increased.
Koog26host/11native cases pass. Logs `/tmp/opencode/pitch-fix-koog-{red,green}.log`.

Final focused evidence: `/tmp/opencode/pitch-fix-koog-final.log`,
`pitch-fix-harness-final.log`, `pitch-fix-measure-final.log` and
`pitch-fix-spring-java-options.log` under the same directory. Spring's complete
11host/11native/1presentation selection also passes with inherited JAVA_TOOL_OPTIONS
preserved and a harmless preference-path option appended. Python13tests include
nonzero-child diagnostic retention and the preserved combined2MiB bound. Exact
acceptance inventories now require Koog26/11 and Spring11/11/1; no mandatory case
or physical-retirement assertion was removed or skipped.

---
<!-- id: issue-20261030-144907-661-cancel-live-incident-http-requests-at-the-agent-de  file: issue-20261030-144907-661-cancel-live-incident-http-requests-at-the-agent-de.md -->
