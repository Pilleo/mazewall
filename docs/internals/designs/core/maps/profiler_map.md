# Profiler knowledge map

The profiler lives in `profiler/src/main/kotlin/io/mazewall/profiler`.

- `Profiler` owns the JVM-facing session and deterministic listener/worker cleanup.
- `internal/TraceListenerMachine` owns socket handshake and event-parser lifecycle; invalid handshakes are terminal.
- `tierE/engine/TierEbpfEngine` owns its transactional eBPF descriptor aggregate.
- `engine` contains the out-of-process USER_NOTIF protocol implementation.

Profiler-specific protocol constraints are documented in `profiler/AGENTS.md`.
