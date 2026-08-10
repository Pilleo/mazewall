---
title: "Correct enforcer README claim about Landlock network isolation"
severity: "LOW"
status: "open"
priority: 4
dependencies: []
component: "docs"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/README.md"
effort: "small"
autonomy: "autonomous"
---

# 🟢 [Severity: LOW]: Correct enforcer README claim about Landlock network isolation

**Context:** The enforcer README states that mazewall integrates Landlock to restrict both filesystem paths and TCP ports. The current Landlock implementation does not enable ABI v4 network rights; its network access mask remains disabled, as recorded by resolved issue 077. Network restriction is currently provided by Seccomp-BPF policy rather than Landlock port rules. The README therefore overstates the implemented enforcement mechanism.

**Needed:** Rewrite the architecture bullet so it attributes filesystem path restriction to Landlock and network syscall restriction to Seccomp-BPF. Explicitly state that Landlock ABI v4 TCP-port rules are not currently enabled. Cross-check the wording against `Landlock.kt`, `PolicyPresets.kt`, and the containment/security design documents, then run the documentation/backlog checks through `./gradlew build`.
