# Tech stack & constraints

What the engineering lenses should assume. The architecture lens keeps the
system model in `knowledge/architecture.md`; this file is the coarse truth.

- **Languages / frameworks:** Kotlin and Java on JDK 25 idioms (JDK 22+ supported), Gradle, JUnit, Kotlin test, FFM, Seccomp-BPF, Landlock, and Linux native syscalls.
- **Infra (where it runs):** Linux JVM processes, with host tests and privileged kernel integration tests when Seccomp, Landlock, or USER_NOTIF behavior changes.
- **Non-negotiables (compliance, uptime, budgets):** Fail closed; preserve JVM coordination syscalls; never install Seccomp from a virtual thread; initialize Landlock before Seccomp; capture errno immediately; keep native layouts behind NativeEngine/io.mazewall.ffi; never combine TSYNC and NEW_LISTENER.
