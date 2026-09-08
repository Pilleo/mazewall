# Enforcer knowledge map

The containment entry points live in `enforcer/src/main/kotlin/io/mazewall`.

- `Platform` provides generation-safe platform capability probes.
- `enforcer/supervisor/SupervisorSessionMachine` is the authoritative notification lifecycle reducer; `SupervisorSessionHandler` interprets its effects.
- `enforcer/engine` installs Landlock before Seccomp and owns the native containment boundary.
- `platform` owns FFM layouts and raw native engine abstractions; it must not be bypassed from policy code.

For the full threat model and ordering constraints see `docs/internals/designs/enforcer/containment-design.md`.
