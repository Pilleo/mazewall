# Kotlin fault parity and short rootful ownership verification (2026-09-25)

Diagnostic validation only. The operator decision remains
`REJECT_DEFAULT_PROMOTION`; hybrid remains opt-in experimental,
`policy_eligibility=false`. No long 75,000-operation matrix was run here.
Tests executed against a source-dirty local checkout and the local rootful
Docker socket; these artifacts are not a clean-source performance decision.

## Fault-coverage parity

`archived-fault-parity.json` records an independent invocation of the Kotlin
`TierEToolsMainKt coverage` command on **all 30** extracted arms from
`docs/internals/evidence/tier-e-hybrid-20260923/fault-fifteen-discovery.tar.zst`
(archive SHA-256 `2d7554a16517ec5142f9f84b09725dc09d89c637e64a1fb9c7e50120277f3b23`).
For each arm the freshly computed JSON exactly matched the parsed values in
its untouched archived `coverage-verdict.json`: coverage `PASS`,
terminal `INCOMPLETE`, policy ineligible. The original archive was not edited.

The `short-ring-drop/` directory holds the oracle, collector-owned manifest,
terminal report, and coverage verdict from a fresh 72-operation rootful
`run_tier_e_split_privilege_fixture.sh` run with
`TIER_E_SPLIT_FAULT_RING_DROP_SYSCALL=257`, recovery enabled, and seed 23063.
The fixture exited 0 and recovered exactly 72 edges: coverage `PASS`, terminal
`INCOMPLETE`, `transport_completeness=false`, `policy_eligibility=false`.
Kotlin unit tests also reject unknown, duplicated, absent, and malformed edges.
Coverage success does not qualify complete event transport.

## Endpoint, cleanup, export, and cache

`TIER_E_CONTAINER_ENGINE=docker MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./gradlew build`
passed. `rootful-docker-junit.xml` records **5/5** short integration cases,
including the real split 72-path fixture, a forced shell-controller SIGKILL
with exact target/collector cleanup and unrelated sentinel survival, and
caller-owned artifact export after a privileged failure. The annotated
condition test was admitted (`rootful-condition-junit.xml`, **1/1**).
There were no remaining `tier-e-junit-*`, `tier-e-killed-*`, or
`tier-e-unrelated-*` containers on the verified rootful Docker server.
Focused Kotlin tests cover scoped cleanup failures, bounded TERM grace,
fail-closed export stages and stale outputs, native-cache foreign-checkout
rejection, incremental reuse, serialized writers, and default/remote endpoint
selection. Shell syntax checks and `./scripts/adkw check-backlog` passed.

`TIER_E_CONTAINER_ENGINE=podman CONTAINER_HOST=unix:///run/podman/podman.sock
./gradlew :profiler:rootfulContainerTest --info` produced **5/5 skips** with
a visible "podman: No such file or directory" endpoint reason
(`absent-podman-junit.xml`). Real rootful Podman runtime parity was **not**
verified. Remote endpoint locality remains exercised by injected discovery
tests, not by a remote server.

`SHA256SUMS` covers these retained receipts, excluding itself. The older
historical archives and checksum ledgers remain unchanged.
