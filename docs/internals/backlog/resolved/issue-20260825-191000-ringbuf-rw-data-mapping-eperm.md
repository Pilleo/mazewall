---
title: "BPF ringbuf data area cannot be mapped writable on current kernels"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "tier-e"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/ringbuf/BpfRingBufferReader.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/ringbuf/BpfRingBufferReaderTest.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: 781128af-02d7-4386-8f97-d5b0aba94413
paperclip_identifier: MAZ-766
---

# 🟡 [Severity: MEDIUM]: Ringbuf data area rejects PROT_WRITE mappings (errno EPERM)

**Context:** Empirically established on kernel 7.1.4-xanmod1 (2026-08-25, WP-04 harness):
mmap(PROT_READ|PROT_WRITE, MAP_SHARED) over a `BPF_MAP_TYPE_RINGBUF` fd fails with EPERM
when the mapping covers the DATA area (either meta+data at pgoff 0, or the data alias at
pgoff=PAGE_SIZE). Permitted shapes:

```
RW meta page only (len = PAGE_SIZE, off = 0)      OK
RO meta+data   (len = PAGE+data,  off = 0)        OK
RO data alias  (len = data,       off = PAGE)     OK
RW anything covering data                         EPERM
```

The classic libbpf single-RW-mapping consumer therefore does not work here; consumers must
map the meta page RW and the data area RO (consumer_pos stays writable through the meta
page). `RingbufReader.kt` implements exactly this two-mapping scheme. Earlier podman-rootful
runs appeared to tolerate the legacy shape; treat any engine/kernel where it works as
version luck, never as contract.

**Needed:**
1. Keep `RingbufReader` on the two-mapping scheme; add a regression test asserting the
   RO-data read path under concurrent producer load in the current profiler reader.
2. When WP-14 lands the pure-syscall loader, preserve identical mapping semantics and add
   an explicit comment cross-linking this issue.
3. If a future kernel relaxes write-mapping permissions, do NOT switch back silently:
   writable data mappings would let the consumer corrupt producer bookkeeping after any
   ACE-in-container scenario — detection-plane hygiene.

## Rootful verification evidence (resolution complete; CI retention deferred)

Implementation verified against the authoritative rootful Docker gate retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md), source commit `d0a5f6d3631f84744fef6ec9417565168f005f90`, with the follow-up evidence commit `e238775c`.

Verification command:

```text
DOCKER_HOST=unix:///var/run/docker.sock TIER_E_CONTAINER_ENGINE=docker ./scripts/run_tier_e_rootful_gate.sh
```

The gate retained native CTest output, profiler unit/integration/fresh-JVM output, benchmark samples, run metadata, and the native-backed Tier E fixture result. Native CTest passed `10/10`; the fresh-JVM Tier E fixture executed with `skipped=0` and `failures=0`; the three-sample full-stream and unique-edge benchmark gates passed their `125%` limits. Machine-readable dimensions are `capture_domain_validity=PASS`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, and `runtime_overhead=PASS`. Rootless Podman remains `INCOMPLETE`.

## Resolution (local rootful evidence)

This record is resolved for the current branch using the successful rootful Docker verification retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md). The evidence is tied to implementation commit `d0a5f6d3631f84744fef6ec9417565168f005f90` and documentation commit `e238775c`; CI retention is explicitly deferred and does not block this local delivery.
