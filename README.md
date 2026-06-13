# mazewall

[![CI](https://github.com/Pilleo/mazewall/actions/workflows/ci.yml/badge.svg)](https://github.com/Pilleo/mazewall/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/Pilleo/mazewall.svg)](https://jitpack.io/#Pilleo/mazewall)

**Per-thread syscall sandboxing for JVM applications — no native code, no root, no restarts.**

> [!WARNING]
> **Experimental.** Not production-ready. Contains known stability and security limitations. Do not deploy in production.

---

## The 30-Second Pitch

You already use Docker's OCI seccomp profile to restrict what syscalls your container can make.
**mazewall brings that same model inside the JVM — per thread, per executor, at runtime.**

When Log4Shell hit, the attacker's code ran on the *exact same thread* as the vulnerable logger — a thread that already had `execve` permission because the rest of the JVM needed it. Container-level profiles can't fix that; they apply to the whole process.

mazewall wraps the executor that runs untrusted work and installs a kernel-enforced filter for those threads only. The filter survives any JVM vulnerability — no bytecode manipulation can remove a seccomp rule once installed.

```kotlin
val safe = ContainedExecutors.wrap(
    Executors.newSingleThreadExecutor(),
    Policy.NO_EXEC                        // blocks execve, fork, memfd_create, io_uring
)

safe.submit { vulnerableLogger.log(maliciousInput) }
// The kernel intercepts execve() → EPERM.
// No shell is spawned. Throws ContainmentViolationException.
```

That's the whole API for most use cases.

---

## Where to Go Next

| I want to… | Go to |
|---|---|
| Install and write my first policy | [GETTING_STARTED.md](GETTING_STARTED.md) |
| See it block real CVEs (Log4Shell, SSRF, XXE) | [Demo README](demos/vulnerable-web-app/README.md) |
| Understand threat model and what it can't stop | [SECURITY_CONSIDERATIONS.md](docs/internals/SECURITY_CONSIDERATIONS.md) |
| Read the deep-dive article series | [Article series](#article-series) |
| Contribute or modify the codebase | [CONTRIBUTING.md](CONTRIBUTING.md) |

---

## How It Works (in plain terms)

mazewall uses two Linux kernel features that have been in Docker since 2014:

| Kernel feature | What it does | Docker equivalent |
|---|---|---|
| **Seccomp-BPF** | Blocks specific syscalls per thread | `--security-opt seccomp=profile.json` |
| **Landlock LSM** | Restricts filesystem paths per thread | `--read-only` + bind mounts |

The difference: Docker applies these at the *container* (process) level. mazewall applies them at the *thread* (executor) level, from inside the JVM, without root privileges and without a native C dependency.

Implementation uses the JDK **Foreign Function & Memory API** (JDK 22+) to make the two `prctl`/`seccomp` syscalls directly from Kotlin.

---

## Built-In Policies

| Policy | Blocks | Typical use |
|---|---|---|
| `Policy.NO_EXEC` | `execve`, `fork`, `memfd_create`, `io_uring_*`, `ptrace` | Process-wide startup lockdown |
| `Policy.NO_NETWORK` | `connect`, `socket`, `sendmsg`, `io_uring_*` | Parsers that need disk but no network |
| `Policy.PURE_COMPUTE_UNSAFE` | All of the above + filesystem writes + `mmap(PROT_EXEC)` | Crypto/image workers |
| `Policy.PURE_COMPUTE` | `PURE_COMPUTE_UNSAFE` + auto-whitelists JVM classpath | Same, without lazy-classload crashes |

Policies are composable via a builder — see [GETTING_STARTED.md](GETTING_STARTED.md#building-a-custom-policy).

---

## Project Modules

| Module | Purpose | Use in production? |
|---|---|---|
| `:enforcer` | Core runtime — zero dependencies beyond Kotlin stdlib | ✅ Yes |
| `:profiler` | Developer tool — profiles a workload and suggests a minimal policy | 🚫 Dev/test only |
| `:demo` | Interactive CLI exploits showcase | 🚫 Demo only |
| `:demo:vulnerable-app` | Spring Boot CVE demo (Log4Shell, SSRF, XXE…) | 🚫 Demo only |

---

## Article Series

Background reading on the kernel mechanics and threat model:

| Part | Title | Focus |
|---|---|---|
| **1** | [Do You Really Know What Your App Is Doing at Runtime?](docs/presentation/article.md) | Seccomp, Landlock, SBoB concepts |
| **2** | [Let Your Code Build Its Own Sandbox](docs/presentation/article2-profiler.md) | Dynamic profiling, USER_NOTIF daemon |
| **3** | [Thread-Scoped JVM Containment: The Mechanics](docs/presentation/article3-enforcement.md) | FFM bridge, GC safepoints, Loom VT |
| **4** | [The Attacks We Actually Stop](docs/presentation/article4-attacks.md) | Log4Shell, fileless malware, io_uring bypass |
| **5** | [Generating an SBoB for Java: What's Missing](docs/presentation/article5-graalvm.md) | Dynamic classloading, GraalVM AOT |
| **6** | [Beyond the Thread: Isolates, WebAssembly, Tooling](docs/presentation/article6-isolates.md) | GraalVM Isolates, WASI, sidecar portals |

---

## License

Apache License 2.0.
