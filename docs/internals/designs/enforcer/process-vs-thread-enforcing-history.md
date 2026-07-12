### 1. The Core Architectural Dilemma: Process vs. Thread

The Linux kernel does not natively distinguish between a process and a thread; both are represented internally as **Tasks** (`task_struct`). Isolation is determined by the flags passed to the `clone()` system call:
* **Processes (Secure but Heavy):** Created *without* the `CLONE_VM` flag. They have entirely independent virtual memory spaces mapped by hardware page tables.
* **Threads (Fast but Shared):** Created *with* the `CLONE_VM` flag. They share the exact same address space.

Because threads share memory, a thread-level system call filter (like Seccomp or Landlock) is technically a **logical boundary, not a physical memory boundary**. If an attacker achieves native Remote Code Execution (RCE) on a sandboxed thread, they can write shellcode directly into the stack or memory of an unrestricted sibling thread, hijacking its execution and forcing it to make system calls on their behalf.

---

### 2. The Language Divide: Why Managed Runtimes Succeed

To make thread-level sandboxing secure, the execution environment must provide a barrier that prevents sibling-thread memory hijacking. This divides the programming language landscape into two distinct security models:

#### A. Managed Runtimes (The Mazewall Paradigm)
Languages like **Java (JVM)** and **C# (.NET)** enforce **runtime memory safety**. The runtime virtual machine acts as a continuous, in-process policing layer.
* **The Boundary:** An attacker restricted to "Java-land" cannot forge raw memory pointers or execute arbitrary assembly. Because they cannot touch sibling threads' memory, thread-level Seccomp and Landlock filters (like those used by Mazewall) act as an unbreakable security boundary against logical exploits (e.g., path traversal, SSRF, arbitrary command execution).
* **The Exception:** If the attacker exploits a low-level C++ vulnerability in the JVM itself (or a native JNI/FFI library), they break into "Native-land." At that point, the runtime boundaries collapse, and the thread-level sandbox is bypassed.

#### B. Native Languages (The Compile-Time Gap)
In native languages like **C, C++, and Rust**, compiled binaries run as raw machine code with no runtime virtual machine guarding memory access.
* **C and C++** have zero memory safety; a buffer overflow gives the attacker native RCE, instantly bypassing any thread-level Seccomp filter.
* **Rust** provides memory safety, but only at *compile-time*. At runtime, if an attacker exploits a memory corruption vulnerability in any `unsafe` block, third-party dependency, or FFI boundary, the compile-time guarantees vanish. Running arbitrary assembly allows the attacker to hijack sibling threads' memory just like in C++.

---

### 3. Hardware and Compiler Defenses for Native Threads

To achieve secure thread-level sandboxing in native environments, systems must use hardware or compiler-level isolation to prevent cross-thread memory corruption:

* **Hardware Memory Protection Keys (MPK / PKU):** Modern x86_64 CPUs (Intel Skylake-SP/AMD Zen 3 onwards) and ARM CPUs (via the modern **Permission Overlay Extension - POE**) allow memory pages to be tagged with hardware keys. Threads can execute rapid user-space register writes (e.g., `WRPKRU`) in about 20 CPU cycles to dynamically lock themselves out of sibling threads' memory domains.
* **GraalVM Isolates:** GraalVM Native Image compiles Java Ahead-Of-Time into native binaries. To prevent thread hijacking, it supports **Isolates** (disjoint heaps) and integrates directly with **Intel MPK** via its `Isolates.ProtectionDomain` API [2.1.1], physically separating native threads at the CPU level [1.2.6].
* **WebAssembly & Software Fault Isolation (SFI):** Native code can be compiled to WASM and run within an in-process runtime (like Firefox's **RLBox** or Rust's **Wasmtime**). The runtime translates pointer operations into offsets within an isolated, linear memory array, physically preventing the compiled code from accessing the host process's address space.

---

### 4. Real-World Case Studies: Thread-Level Sandboxing in Practice

While process-level sandboxing is the industry standard for high-security boundaries, many teams have built or experimented with thread-level isolation to reduce IPC latency, bypass GIL limitations, or isolate third-party plugins:

```
                          THREAD-LEVEL SANDBOXING EXAMPLES

  [ Historical Linux ] <--- Seccomp was strictly thread-local before TSYNC (Linux 3.17)

  [ Syd / Sydbox ]     <--- Rust engine using per-thread namespace unsharing (CLONE_FS)

  [ HODOR ]            <--- Node.js sandbox dividing Main Event Loop vs. Native Thread Pool

  [ Threadbox ]        <--- Pledge-style Python decorators mapping tasks to sandboxed threads
```

* **Historical Linux (Pre-3.17):** Prior to the introduction of the Seccomp `TSYNC` flag in late 2014, Seccomp filters in Linux were strictly thread-local. Multi-threaded applications of that era (such as early implementations of **Memcached**) had to manually iterate through and apply distinct Seccomp filters to individual worker threads.
* **Syd / Sydbox:** A sandboxing and container engine written in Rust that utilizes **per-thread namespace isolation**. It calls `unshare(2)` with `CLONE_FS` and `CLONE_FILES` on individual helper threads to isolate their file-descriptor and working-directory tables, preventing TOCTOU race conditions [1.1.3].
* **HODOR:** An academic sandboxing framework for **Node.js** that separates system call access by thread function. It applies highly restrictive Seccomp filters to the JavaScript event-loop thread, while applying targeted, functional filters to the background native thread pool (Libuv) [1.1.4].
* **Threadbox:** A security research framework that implements OpenBSD-style `pledge()` sandboxing at the function level. In Python, developers can decorate a function with `@sandbox_function(promises=["rpath"])`, which automatically spawns the function on a dedicated OS thread restricted by a thread-local Seccomp/Landlock profile [1.2.6, 1.2.8].
* **Chromium (Historical & Current):**
  * *The 2009 Attempt:* Chromium originally experimented with an "untrusted thread / trusted helper thread" model, but abandoned it due to extreme engineering complexity, TOCTOU vulnerabilities, and the emergence of **Spectre/Meltdown** (which allow speculative memory reads across threads in the same page table, making process boundaries mandatory).
  * *Modern Mitigations:* While Chromium uses separate OS **Utility Processes** for untrusted C++ libraries, it uses **Intel PKU** inside the renderer to safely JIT-compile WebAssembly with fast Write-XOR-Execute toggles, and uses the **V8 Heap Sandbox** (Software Fault Isolation) to trap JavaScript memory corruption.

---

### 5. Architectural Guide: Implementing the "Mazewall Idea"

To build a secure, lightweight, thread-level sandbox across different programming languages, developers can follow these architectural patterns:

| Language | Memory Isolation Mechanism | Sandboxing Mechanism | Implementation Strategy |
| :--- | :--- | :--- | :--- |
| **Java / C#** | Language VM (JVM / .NET CLR) | Native Seccomp / Landlock | Apply filters directly to OS threads; rely on runtime type-safety to prevent sibling thread memory access. |
| **Python** | Python VM (CPython) | PEP 684 / Seccomp | Run untrusted code in **isolated subinterpreters** on dedicated threads. Use audit hooks to **block C-extensions and `ctypes`** to keep the threat strictly inside pure bytecode. |
| **Rust** | Compile-time Borrow Checker | Seccomp + `#[forbid(unsafe_code)]` | Audit the dependency tree (using `cargo-geiger`) to ensure zero `unsafe` or C-FFI exists. |
| **C / C++ (Hardware)** | Intel MPK / ARM POE | Seccomp + Hardware Keys | Allocate memory keys via `pkey_alloc()`. Bind the thread to the key and restrict access via `WRPKRU` before applying Seccomp. |
| **C / C++ (Software)** | WebAssembly Linear Memory | WASI / In-Process JIT | Compile the C++ module to WASM. Run it on an OS thread inside Wasmtime, restricting system calls via WASI. |
