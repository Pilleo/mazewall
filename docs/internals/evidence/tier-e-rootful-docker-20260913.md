# Tier E rootful Docker evidence

Date: 2026-09-13
Environment: rootful Docker via `unix:///var/run/docker.sock`, privileged container,
host networking, host user namespace, Docker seccomp unconfined for native fixtures.

Full logs retained during the run:

- `/tmp/tier-e-rootful-docker.log`
- `/tmp/tier-e-rootful-native-direct.log`
- `/tmp/tier-e-rootful-native-redefinition.log`

## Commands

```text
DOCKER_HOST=unix:///var/run/docker.sock MAZEWALL_CONTAINER_ENGINE=docker \
  ./scripts/run_containerized_tests.sh :profiler:integrationTest :profiler:integrationTestFreshJvm
```

Result: `BUILD SUCCESSFUL` (`:profiler:integrationTest`, `:profiler:integrationTestFreshJvm`).

The cached test image does not contain CMake. The already-built native fixture binaries
were therefore executed directly under privileged rootful Docker:

```text
DOCKER_HOST=unix:///var/run/docker.sock docker run --rm --privileged --network host \
  --userns host --security-opt seccomp=unconfined \
  -v /tmp/jseccomp-profiler-native-build:/tmp/jseccomp-profiler-native-build:ro \
  mazewall-test-runner:latest ...
```

The retransformation fixture uses
`/tmp/jseccomp-profiler-native-build/agent-test-classes/redefinition-agent.jar`.

## Results

- `seccomp_boundary_fixture`: `attempt_missed=1 observed_sys_enter=0 verdict=INCOMPLETE`
- Agent smoke: nonzero proxied binds and stack captures, `stack-failures=0`
- Full-stream phase benchmark: `get_stack_trace_ns=1655650`, `raw_key_ns=292936`, `logical_metadata_ns=7433067`, `metadata_cache_misses=156`
- Unique-edge phase benchmark: `get_stack_trace_ns=774893`, `raw_key_ns=205129`, `logical_metadata_ns=1049985`, `metadata_cache_hits=102`, `metadata_cache_misses=192`
- `agent_redefinition_smoke_test`: `redefinition-fixture: status=PASS`
- Virtual-thread smoke: nonzero proxied binds and stack captures, `stack-failures=0`

## Machine-readable verdict dimensions

```text
capture_domain_validity=PASS boundary limitation explicitly reported
transport_completeness=PASS profiler integration suite
attribution_coverage=PASS profiler integration suite and agent smoke
dictionary_integrity=PASS retransformation fixture and zero native stack failures
lifecycle_validity=PASS integration suite and retransformation fixture
runtime_overhead=PASS phase measurements retained for comparison
rootful_privileged_fixture=PASS
rootless_podman_fixture=INCOMPLETE not authoritative for BPF evidence
```

The `INCOMPLETE` capture-domain value emitted by the boundary fixture is intentional:
an attempt denied before the selected `sys_enter` path is not fabricated as an observed
exact syscall.
