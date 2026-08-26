# ebpf-prototype

Container harness for Tier E kernel-phase testing.

**No C code.** The eBPF program is built as Kotlin instruction lists in
`:profiler` (`TierEbpfEngine`) and loaded via `bpf(2)` FFM downcalls.
This directory only contains the privileged-container runner scripts
and the JVM base image definition.

## Layout

- `container/Containerfile.kt-runner` — Temurin JDK 25 + bash; nothing else
- `scripts/run_wp05.sh` — host entrypoint: builds `:profiler:installDist`,
  launches privileged container, runs `_container_inner_wp05.sh`
- `scripts/_container_inner_wp05.sh` — starts daemon + stress driver inside container

## Running

```bash
sudo ./scripts/run_wp05.sh
```
