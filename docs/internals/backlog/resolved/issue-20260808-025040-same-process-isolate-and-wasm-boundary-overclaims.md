---
title: "Same-Process GraalVM Isolate and WebAssembly Boundaries Are Overstated"
severity: "HIGH"
status: resolved
priority: 1
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/presentation/article6-isolates.md"
  - "docs/internals/designs/core/security-considerations.md"
  - "docs/internals/designs/enforcer/process-vs-thread-enforcing-history.md"
effort: "large"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Same-Process GraalVM Isolate and WebAssembly Boundaries Are Overstated

**Context:** The documentation describes GraalVM Isolates as physical memory isolation that native pointers cannot cross, claims independent thread models make thread hopping structurally impossible, and compares the result with Firecracker or gVisor. It also describes in-process Wasm as absolute shared-nothing isolation where host memory is necessarily untouched. Separate managed heaps and Wasm linear-memory bounds are valuable language/runtime invariants, but components still inhabit one native address space unless an external process is used. Native ACE or a runtime/JIT/host-function vulnerability is outside those invariants. Oracle's GraalVM sandbox guidance explicitly distinguishes same-process isolated execution from the stronger separate address and signal domains of an external process.

**Needed:** Rewrite the isolation comparison around attacker levels: valid guest code, managed-language compromise, runtime escape, and native ACE. Verify which `Isolates.ProtectionDomain` mode is actually configured before claiming hardware protection. Remove uncited performance/density guarantees and virtualization equivalence. Present a separate OS process with independent credentials, namespaces, limits, and IPC validation as the boundary for hostile native code or runtime compromise.
