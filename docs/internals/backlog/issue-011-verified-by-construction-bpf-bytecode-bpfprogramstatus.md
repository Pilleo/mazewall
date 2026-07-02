---
title: "Verified-by-Construction BPF Bytecode (BpfProgram<Status>)"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Verified-by-Construction BPF Bytecode (BpfProgram<Status>)

**Context:** BPF filters are constructed via string builders or manual instruction lists and passed directly to the kernel. A typo or structural error in a jump target or instruction boundary results in a runtime error or, worse, a kernel validation failure that triggers a fallback/bypass.
**Needed:**
1. Introduce a phantom type `BpfProgram<Status>` where `Status` is `Unverified` or `Verified`.
2. Provide a builder DSL that generates instructions into `BpfProgram<Unverified>`.
3. Require passing `BpfProgram<Unverified>` through an in-memory/in-app BPF static verifier or a local compilation dry-run to produce `BpfProgram<Verified>`.
4. Enforce that `PureJavaBpfEngine.install` only accepts `BpfProgram<Verified>`, guaranteeing that only mathematically verified filters can ever be loaded into the kernel.
