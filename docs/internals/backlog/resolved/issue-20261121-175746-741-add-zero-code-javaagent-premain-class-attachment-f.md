---
title: "Add zero-code -javaagent Premain-Class attachment for mazewall-lockdown"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "lockdown/src/main/kotlin/io/mazewall/lockdown/Mazewall.kt"
target_symbols: 
  - "Mazewall"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add zero-code -javaagent Premain-Class attachment for mazewall-lockdown

**Context:**
Post-0.1.0 enhancement from the v1 hardening spec: allow operators to attach io.mazewall:mazewall-lockdown via -javaagent:mazewall-lockdown.jar=<preset/profile/syscalls> for legacy applications without modifying application main(). Needs explicit opt-in Premain-Class / Can-Redefine-Classes manifest rules, strict preset/syscall argument validation, and packaged JAR subprocess tests.

**Needed:**
1. Design the -javaagent Premain-Class entrypoint and argument grammar in lockdown/ and verify modular/classpath agent startup without auto-activating on normal library classpath usage.

## Resolution Evidence (2026-11-21)
- Release scope decision: the implementation below is historical. The agent is excluded from `0.1.0`; lockdown requires an explicit application startup call.
- Added `MazewallAgent` (`premain(String?)`, `parsePolicy(String?)`), manifest `Premain-Class`, `VerifyLockdownPublication` enforcement, `lockdown/api/lockdown.api`, unit tests (`MazewallAgentTest`, `LockdownJavaApiTest`), and packaged `-javaagent` subprocess verification in `MazewallLockdownIntegrationTest`.

---
<!-- id: issue-20261121-175746-741-add-zero-code-javaagent-premain-class-attachment-f  file: issue-20261121-175746-741-add-zero-code-javaagent-premain-class-attachment-f.md -->
