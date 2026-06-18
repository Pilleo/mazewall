 Here is a breakdown of the problem, the kernel constraints we must navigate, and the proposed architectural fix.

  The Core Problem
  The Tier S Profiler uses the Linux SECCOMP_FILTER_FLAG_NEW_LISTENER flag to create a USER_NOTIF file descriptor. By default, this flag only applies the filter to the calling thread.
  Because background JVM threads (like the Garbage Collector and JIT compiler) are spawned by the JVM before your application code calls Profiler.profile { ... }, they do not inherit the filter. Therefore, the profiler is completely blind to
  them, resulting in an incomplete BillOfBehavior.

  The Constraint
  The issue mentions two paths, but the mazewall engineering guidelines (AGENTS.md) contain a strict hard boundary:
  > "Never combine SECCOMP_FILTER_FLAG_TSYNC and SECCOMP_FILTER_FLAG_NEW_LISTENER."

  The kernel enforces this mutual exclusivity because returning both a file descriptor (for the listener) and a potential failing thread ID (for TSYNC failures) in a single system call creates an unresolvable return-value ambiguity on pre-5.14
  kernels.

  The Solution: The "Two-Step TSYNC" Strategy
  We cannot use them in a single seccomp call, but we can use them sequentially to achieve process-wide profiling.

  We will introduce a processWide: Boolean = false flag to Profiler.profile. When enabled, ProfilerInstaller will execute the following sequence:

   1. Step 1 (Install Listener): Call seccomp with SECCOMP_FILTER_FLAG_NEW_LISTENER and the actual profiling BPF program.
      * Result: The calling thread now has the USER_NOTIF filter, and we hold the listener FD.
   2. Step 2 (Synchronize Tree): Create a benign "dummy" BPF program (e.g., a single instruction: return SECCOMP_RET_ALLOW). Call seccomp again with SECCOMP_FILTER_FLAG_TSYNC and this dummy program.
      * Result: The kernel's TSYNC semantics mandate that it copies the entire filter tree of the calling thread to all other sibling threads. Because the calling thread's tree now includes the USER_NOTIF filter from Step 1, all JVM background
        threads will receive it.
   3. Outcome: When a GC or JIT thread makes a system call, it will hit the synchronized filter, and the notification will be routed to the exact same listener FD created in Step 1. The existing daemon loop will automatically process events
      from these background threads.

  Why "Clone Tracking" (Path 2) is insufficient
  The backlog issue mentions "Clone Tracking" as an alternative. While inheriting filters across clone() catches newly spawned worker threads, it fundamentally cannot catch the GC or JIT threads because they are pre-existing. The Two-Step TSYNC
  is the only way to accurately map the true "JVM Floor".

  The no_new_privs Caveat
  As noted in a previously resolved issue in the backlog, TSYNC requires no_new_privs to be set on all threads. This means Profiler.profile(processWide = true) will deterministically throw an EACCES exception if run directly on a host
  macOS/Linux machine via ./gradlew.

  We will need to catch this specific EACCES exception during Step 2 and throw a clear IllegalStateException advising the developer that process-wide profiling requires running the suite inside a container (e.g., via the provided podman /
  ./scripts/run_tests.sh scripts) where no_new_privs is established at the container boundary.
