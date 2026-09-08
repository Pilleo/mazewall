# Current state — as-built inventory

> What ALREADY EXISTS. Every design lens grounds its review here: a design
> is judged as a DELTA against this inventory, never in a vacuum.
> Reinventing a listed component, or contradicting a fact below, is a
> blocker-class finding. Keep it short and true; record the big as-built
> choices as ACCEPTED decisions (`tp decision new "<title>" --modules
> <globs>`) so they also govern future work automatically.

- **Built & running (components, who owns them):** `:platform` owns FFM/native primitives; `:enforcer` owns Seccomp and Landlock containment; `:profiler` owns USER_NOTIF profiling; `:portal`, `:portal-codegen`, and `:portal-worker` provide broker/worker isolation.
- **Data & integrations that exist (sources, pipelines, stores):** Native Linux syscall interfaces, process/socket FD passing, profiler daemon transport, and generated portal stubs.
- **Hardware / physical constraints already in place:** Linux-only kernel API behavior; Seccomp filters are irreversible and thread-bound; Landlock rules are irreversible for the process/thread scope selected.
- **In flight (started, not landed):** Broad refactoring toward evaluate-plus-effects state machines and stronger type/state boundaries across platform, enforcer, profiler, and portal; the working tree contains user-owned changes in these areas.
- **Known debt on the built parts:** `MAZ-1175` should separate Landlock install evaluation from native effects; `MAZ-1176` depends on it to move containment registry updates into install-machine effects. Additional queued work covers USER_NOTIF, profiler handshakes, portal RPC, API visibility, and portal method IDs.
