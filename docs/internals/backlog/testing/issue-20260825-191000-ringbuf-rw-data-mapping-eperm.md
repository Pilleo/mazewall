---
title: "BPF ringbuf data area cannot be mapped writable on current kernels"
severity: "MEDIUM"
status: "open"
priority: medium
component: "tier-e"
target_modules:
  - "tier-e-proto"
target_files:
  - "tier-e-proto/src/main/kotlin/io/mazewall/tierE/ringbuf/RingbufReader.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
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
   RO-data read path under concurrent producer load (WP-05 stress covers this).
2. When WP-14 lands the pure-syscall loader, preserve identical mapping semantics and add
   an explicit comment cross-linking this issue.
3. If a future kernel relaxes write-mapping permissions, do NOT switch back silently:
   writable data mappings would let the consumer corrupt producer bookkeeping after any
   ACE-in-container scenario — detection-plane hygiene.
