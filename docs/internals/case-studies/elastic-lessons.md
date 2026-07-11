# Lessons from Elasticsearch Seccomp Implementation

This document captures architectural insights and research findings from the Elasticsearch (ES) system call filter implementation. It serves as a reference for `mazewall` to validate its own security boundaries and identifies potential improvements in bootstrap verification and syscall coverage.

## 1. The JNA / `libc` Binding Approach

Elasticsearch avoids the complexity of shipping and loading custom native libraries (`.so` files) by using **JNA (Java Native Access)** to bind directly to the host system's standard C library (`libc`).

- **Mechanism:** They use `Native.loadLibrary("c", ...)` to link against `libc.so.6`.
- **Benefit:** This allows them to invoke kernel-level system calls like `prctl` (for seccomp) and `mlockall` (for memory locking) without writing a single line of C code or maintaining a native build pipeline.
- **Architectural Match:** `mazewall` achieves a similar (but more modern) result using the **JDK 22+ Foreign Function & Memory (FFM) API**, which also binds directly to `libc` symbols with zero-overhead calling conventions.

## 2. Evasion of `noexec` /tmp Issues

A common failure mode for JNI/JNA libraries is the extraction of helper `.so` files into `/tmp`. If `/tmp` is mounted with the `noexec` flag, the JVM cannot load these libraries, leading to initialization failures.

- **The ES Solution:** By binding to the *system's* `libc`, they rely on a library that is already loaded and resides in a trusted, executable location (e.g., `/lib/x86_64-linux-gnu/`).
- **JNA Dispatch:** JNA still requires `libjnidispatch.so`. Elasticsearch handles this by allowing users to override `jna.tmpdir` or by assuming the library is installed globally via the OS package manager.
- **Mazewall Context:** `mazewall`'s use of FFM is even more resilient, as FFM does not require any library extraction; it links directly to the process's existing address space.

## 3. BPF Structure: Linear Scan vs. BST

Elasticsearch's BPF (Berkeley Packet Filter) program is significantly simpler than `mazewall`'s, which dictates their choice of data structure.

### Elasticsearch: Linear Scan
- **Strategy:** Blacklist.
- **Complexity:** $O(N)$ where $N$ is the number of blocked syscalls.
- **Reasoning:** ES only blocks a handful of high-risk syscalls (typically 4: `fork`, `vfork`, `execve`, `execveat`). A linear sequence of `BPF_JEQ` instructions is extremely fast for such a small set and avoids the complexity of building a jump table or tree.

### Mazewall: Binary Search Tree (BST)
- **Strategy:** Whitelist (Tier 1 & Tier 2).
- **Complexity:** $O(\log N)$ where $N$ is the total number of allowed syscalls (typically 100-300 for a JVM).
- **Reasoning:** Because `mazewall` defaults to "Deny All," it must whitelist hundreds of syscalls. A linear scan of 200 instructions would be a massive performance tax on every system call. `mazewall` uses a **Binary Search Tree** approach to resolve the allow/deny action in $\approx 7-9$ jumps.

## 4. Actionable Learnings for `mazewall`

### A. Exhaustive RCE Blocking
Elasticsearch meticulously blocks syscalls that can be used to pivot to a shell. `mazewall` must ensure its Tier 1 `NO_EXEC` baseline is equally exhaustive:
- **`execveat`**: Often overlooked if only `execve` is blocked.
- **`vfork`**: A lighter version of fork that can still be used for process creation.
- **`clone`**: Must be restricted to only allow `CLONE_THREAD` (preventing raw process spawning).

### B. The "Bootstrap Check" Philosophy
ES treats security containment as a mandatory prerequisite for production.
- **Proposal:** Implement a `mazewall` "Pre-flight Check" that verifies `CONFIG_SECCOMP`, `CONFIG_LANDLOCK`, and architecture-specific syscall mappings *before* the application starts.
- **Fail Fast:** If the kernel is too old or features are missing, the JVM should crash immediately rather than running in an insecure state.

### C. Multi-Architecture Parity
ES has strong support for `s390x` and `ppc64le` in their BPF assembly. While `mazewall` focuses on `x86_64` and `aarch64`, the ES mapping logic provides a template for expanding to other enterprise architectures.

## 5. Where `mazewall` Excels

- **Deep Argument Inspection:** ES mostly blocks syscalls by number. `mazewall` inspects arguments (e.g., blocking `mmap` and `mprotect` *only* when `PROT_EXEC` is requested), which allows for a much tighter security posture without breaking legitimate JVM memory management.
- **Thread-Scoped Containment:** ES is process-wide. `mazewall` can apply different policies to different thread pools (Tier 2), providing "Security in Depth" within a single JVM process.
- **Modern FFI:** By using FFM instead of JNA, `mazewall` avoids the performance overhead of JNA's reflective-style dispatch and benefits from JDK-enforced memory safety (`Arena`, `MemorySegment`).
