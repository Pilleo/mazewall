# Profiler knowledge map

This historical entry point redirects to the [canonical architectural knowledge map](../architectural-map.md)
and [profiler design and generated diagram](../../profiler/profiler-design.md).
The profiler lives in `profiler/src/main/kotlin/io/mazewall/profiler`: the compatibility facade delegates
to an internal service, whose sessions own listeners, TID mappings, evidence buffers, and cleanup.
Profiler-specific protocol constraints remain in `profiler/AGENTS.md`.
