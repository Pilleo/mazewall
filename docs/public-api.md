# Supported Public API

`0.1.0-SNAPSHOT` is the first intentionally versioned Mazewall API baseline.

Supported application contracts are limited to:

- `io.mazewall`: configuration, policies, installation assessment and receipts;
- `io.mazewall.enforcer.api` and `io.mazewall.enforcer.diagnostics`: contained execution, violations and diagnostics;
- `io.mazewall.profiler.api`: profiling options, sessions, results and behavioural evidence.

Native engines, syscalls, FFM layouts, descriptor ownership, BPF programs, daemon protocols, attribution, replay and collector types are implementation details. They are not extension points and may change without compatibility notice. The Tier E daemon launcher is an executable JVM entrypoint, not a library API.

The checked-in Kotlin ABI dumps in `enforcer/api`, `profiler/api`, and `platform/api` are enforced compatibility baselines. A deliberate public API change must update the relevant dump and this document in the same review.

## Migration from native and platform APIs

Direct imports of `platform`, `core`, `ffi`, syscall, BPF, daemon, attribution,
replay, and collector implementation types were removed in `0.1.0-SNAPSHOT`.
Applications must install containment through `Mazewall` and inspect receipts,
diagnostics, and violations through the enforcer facade. Profiling must start
through `MazewallProfiler` and consume its session, result, evidence, and
coverage contracts.

For portal dedicated-worker startup, a plain `BillOfBehavior.toJson()` is not
completion evidence. Persist `ProfilingResult.toProfileDocumentJson()` and supply
that versioned `ProfileDocument` JSON to `--complete-profile`; the worker rejects
legacy attestation markers, missing evidence, and incomplete measured coverage.

When an application combines a process-wide baseline with later thread-scoped
stacktrace-scoped policies, call `Supervision.prepareSupervisor()` before installing
the permanent baseline. It starts required supervision without exposing daemon
state, descriptors, or protocol types.

Repository implementation modules may use narrowly configured Kotlin friend
access while compiling their own production and test code. Published consumers
never receive that friend access, and `platform` is an implementation dependency
of the supported libraries rather than a transitive compile API.

`verifySupportedApiSurface` runs with the normal `check` gate after Kotlin ABI
validation. It rejects implementation namespaces in `enforcer/api/enforcer.api`
and `profiler/api/profiler.api`; an API change therefore needs both an intentional
ABI update and this documented supported-surface review.
