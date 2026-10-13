---
title: "Make supervised agent SSRF controls deterministic and local"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "demos"
target_modules: []
target_files:
  - "demos/agent-sandbox-demo/src/main/kotlin/io/mazewall/demo/agent/MockAttackLlm.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Make supervised agent SSRF controls deterministic and local

**Context:**
MockAttackLlm deterministically emits tool calls, but ssrf targets169.254.169.254 and its unsafe comparison depends on host routing. AgentTools already has acquired-loopback tests; live presentation must preserve exact custom CONNECT and VFORK/FORK handlers and early VFORK bootstrap.

**Needed:**
1. Add an owned loopback HTTP SSRF fixture with unprotected reachability and protected denial of the same private destination; show file_legit and rce_legit positives, retain original default classifier and named authorization frame, and join fixture/process/worker owners on all exits.

**Acceptance:** Offline comparison reaches a genuinely listening private target; protected mode denies that same target through the unchanged production classifier. Missing listener/unrelated timeout is infrastructure failure. Exact CONNECT + VFORK/FORK and early VFORK behavior remain. A real global-unicast positive may remain a separately labeled optional online check; never permit loopback in the real classifier to manufacture a positive.

---
<!-- id: issue-20261013-005317-053-make-supervised-agent-ssrf-controls-deterministic-  file: issue-20261013-005317-053-make-supervised-agent-ssrf-controls-deterministic-.md -->
