# Profiler Module Architecture

This document maps the architectural design and class hierarchy of the `:profiler` module.

## Core Class Diagram

The following diagram illustrates the relationships between the profiler daemon, the memory reader, trace listener, the IPC transport layer, and the SBoB compiler.

<img src="../diagrams/profiler_class_diagram.svg" width="100%">

## Process-Wide Profiling (Two-Step TSYNC Strategy)

By default, the `USER_NOTIF` file descriptor (listener) generated via `SECCOMP_FILTER_FLAG_NEW_LISTENER` only applies to the calling thread. Background JVM threads (such as GC, JIT compiler, or ForkJoinPool threads) spawned during JVM startup do not inherit this filter, making the profiler blind to them.

To achieve process-wide profiling without violating the kernel restriction against combining TSYNC and NEW_LISTENER flags in a single seccomp call, the profiler executes a sequential **Two-Step TSYNC Strategy** when `processWide = true` is requested:

### 1. Step 1: Install Listener
The calling thread installs the main profiling BPF filter using `SECCOMP_FILTER_FLAG_NEW_LISTENER`. The resulting listener FD is retrieved and sent to the supervisor daemon.

### 2. Step 2: Synchronize Filter Tree
The calling thread compiles a benign dummy BPF program that permits all syscalls (`BpfProgram.dsl(arch) { allow() }`) and installs it using `SECCOMP_FILTER_FLAG_TSYNC`. 
The kernel's TSYNC semantics mandate that it copies the entire seccomp filter tree of the calling thread to all sibling threads in the thread group. Since the calling thread's tree already contains the `USER_NOTIF` filter from Step 1, all pre-existing and background JVM threads receive it. Syscall notifications from these background threads are routed to the exact same listener FD created in Step 1.

### 3. Container & Privilege Boundary (`no_new_privs`)
The kernel requires `no_new_privs` to be set on all sibling threads in the thread group for TSYNC to succeed. Pre-existing JVM background threads do not automatically inherit `no_new_privs` if it was set after their creation on the host.
- **On host environments:** Running process-wide profiling directly on the host machine will throw an `EACCES` (errno 13) exception.
- **In containerized environments:** The container boundary establishes `no_new_privs` at initialization (e.g., via Podman/Docker or Kubernetes `allowPrivilegeEscalation: false`), allowing TSYNC to succeed and process-wide profiling to function.

