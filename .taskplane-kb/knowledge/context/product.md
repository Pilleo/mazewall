# Product context

What this product is, who it serves, what "good" means here. The product
persona reads this before shaping requirements; the on-demand north-star
review measures every strategic call against the Direction line below.

- **Direction / north star:** Make Linux JVM containment dependable, explicit, and auditable without weakening kernel-enforced security boundaries.
- **Product:** Mazewall, a Linux JVM sandboxing library built on Seccomp-BPF, Landlock, and the JDK Foreign Function & Memory API.
- **Users / customers:** Java and Kotlin service, platform, and security engineers who need to restrict untrusted or high-risk workloads on Linux.
- **Current goals (what "good" looks like this quarter):** Refactor containment and profiling control paths into typed, explicit state machines while preserving fail-closed behavior and reviewable kernel effects.
- **What we say no to:** Silent security fallbacks, breaking public APIs, new dependencies without approval, and claims that thread-scoped containment is a complete isolation boundary.
