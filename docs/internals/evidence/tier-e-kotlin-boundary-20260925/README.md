# Profiler-owned Tier E test support and lifecycle checks (2026-09-25)

Developer-only, source-dirty verification; not a performance or promotion
decision. Hybrid remains opt-in, `policy_eligibility=false`, and the operator's
`REJECT_DEFAULT_PROMOTION` decision stands. Earlier archives and checksum
ledgers have not been modified.

Tier E-specific container owner, creation coordinator, lifecycle, native
builder, exporter tests, and their unit tests now live in the profiler test
source set. Generic root test helpers still provide bounded command execution,
endpoint probing, and conditional JUnit admission. The profiler integration
source set consumes its test output without adding a runtime dependency.

After a controller failure, unresolved creation prevents destructive
container cleanup. Currently visible run containers receive a best-effort
stop, while their source containers and shared scratch remain recoverable;
sentinel cleanup is independently attempted and errors are suppressed onto
the primary failure. Kotlin allocates the exact shared path before starting
the shell; a forged same-user path marker cannot redirect deletion. Tests
assert the delayed real Docker creation completed, cleanup supplied no
unexpected suppressed exceptions, and no owned containers remained.

`rootful-docker-junit.xml` records **8/8** rootful Docker tests, zero skips or
failures, on the local `unix:///var/run/docker.sock` server from a successful
`TIER_E_CONTAINER_ENGINE=docker MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
./gradlew build` (271 tasks, 5m22s). Focused root generic/profiler Tier E
tests and the full build passed. `absent-podman-junit.xml` records **8/8**
JUnit skips with a visible missing-Podman endpoint reason; real rootful
Podman parity remains unverified. `fault-replay.json` records **30/30** exact
Kotlin JSON verdict matches against the retained ring-drop archive after
requiring independent nonblank registration IDs and positive session epochs
in both manifest and terminal report. Targeted mutation tests reject two
simultaneously missing identities.

`SHA256SUMS` covers these four files, excluding itself.
