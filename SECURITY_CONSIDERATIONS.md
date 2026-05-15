# Security Considerations & Technical Risks

Using seccomp-bpf within the JVM introduces specific architectural risks. This document outlines high-level security properties and implementation trade-offs.

---

## 1. Thread Pool Poisoning & Carrier Contamination
Seccomp filters are **permanent** and **additive**. Applying a filter to a thread that is later reused (e.g. in a shared pool or as a Virtual Thread carrier) will unexpectedly restrict unrelated tasks.
**Mitigation:** These risks are strictly managed by the library. See the **Javadocs in `ContainedExecutors`** for detailed usage requirements and built-in guardrails.

## 2. Executive Control: Argument Inspection
`contained-executors` uses BPF argument inspection to provide fine-grained control over critical syscalls, allowing the JVM to function while blocking malicious actions.

### Executable Memory Protection (`mmap`)
We inspect the `prot` argument of `mmap`. Standard mappings are allowed, but the library triggers an immediate `EPERM` if the `PROT_EXEC` (0x04) bit is set. This blocks binary shellcode execution while allowing the JIT and GC to function normally.

### JVM Stability Protection (`clone`)
We inspect the `flags` argument of `clone`. We allow `clone` only if it includes `CLONE_THREAD` or `CLONE_VM` (indicating a new thread). Standard process forking (`fork`) is blocked. `clone3` is blocked with `ENOSYS` to force runtimes to fallback to the inspectable legacy `clone`.

## 3. The Pure-Java Rationale
Modern Linux supports `SECCOMP_RET_USER_NOTIF`, which allows a supervisor thread to intercept syscalls and inspect complex pointer arguments. 

However, implementing `USER_NOTIF` purely via the Java FFM API is extremely brittle. It requires hardcoding kernel structure layouts that vary across architectures and versions. A mismatch causes silent failures or JVM deadlocks.

**The Decision:** To maintain a 100% pure Java library that is lightweight and robust, we rely on the `SECCOMP_RET_ERRNO` mechanism. This provides mathematically provable enforcement without the distribution complexity of bundled native C/Rust binaries.

## 4. Information Leaks (Side Channels)
Seccomp restricts **actions** (syscalls), but it does not provide **data isolation**. 
*   A contained thread can still read any static variable or heap object it can reference.
*   It can use side channels (CPU timing, cache contention) to leak data to another, non-contained thread.

Seccomp is a "blast radius" mitigator for I/O and execution; it is **not** a replacement for internal Java security boundaries or data encryption.

---

## Summary: Security vs. Stability

| Policy | Security Level | Stability Risk | Best Use Case |
| :--- | :--- | :--- | :--- |
| `NO_EXEC` | High | Low | Web controllers, log processing. |
| `NO_NETWORK` | High | Medium | Data parsing, report generation. |
| `PURE_COMPUTE`| Critical | High | Pure algorithmic tasks (image processing, crypto). |
