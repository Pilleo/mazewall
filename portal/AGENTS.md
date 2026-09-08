# Process portal guidance

The portal is the broker/worker process split. It is distinct from the in-process syscall supervisor.

- Preserve the broker/worker capability-FD boundary; do not substitute ambient path or process authority for a capability FD.
- Read `docs/internals/designs/enforcer/process-portal-design.md` before changing protocol, lifecycle, or capability transfer behavior.
- Keep portal behavior process-scoped. Do not import thread-scoped seccomp-supervisor assumptions into this module.

Verification: `./gradlew :portal:test`
