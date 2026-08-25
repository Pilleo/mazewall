---
title: "Tier E R4: tier-e-doctor environment preflight"
severity: "MEDIUM"
status: "open"
priority: high
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
  - "tier-e-proto"
target_files:
  - "scripts/tier_e_doctor.sh"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies: []
---

# 🟡 [Severity: MEDIUM]: R4 — tier-e-doctor environment preflight

**Context:** Every environment failure in WP-02..04 was discoverable in seconds but was
found by minutes of container debugging: rootless-podman socket masquerading as docker,
image stores split across engines, missing `docker` group, GLIBC floor mismatch between
build host and runtime image, JDK 25 native-access flag absent from launchers, missing
`systemtap-sdt-dev`, kernel/BTF/caps ceilings. All findings are journaled
(testing/issue-20260825-090500 and WP-02..04 progress notes) but not operationalized.

**Needed:** one `scripts/tier_e_doctor.sh` that CHECKS AND EXPLAINS (never silently fixes):

1. Kernel ≥ 5.15 + `/sys/kernel/btf/vmlinux` present.
2. Backend resolution result (root / rootful docker / rootless podman warning) incl.
   `DOCKER_HOST` podman-leak detection with the exact remedy (`usermod -aG docker`,
   `unset DOCKER_HOST`, sudo fallback).
3. Runner images present per store; rebuild commands printed.
4. Built binaries' GLIBC max-version vs selected runtime base (objdump scan).
5. Launchers contain `--enable-native-access=ALL-UNNAMED`.
6. Toolchain presence: clang, llvm-strip, bpftool, libelf/zlib headers,
   `sys/sdt.h` (only when USDT work is expected).
7. Host caps summary (CapEff, init-userns membership via uid_map probe).

Exit nonzero only when the REQUESTED phase cannot run; every failure prints the exact
remedy. Wire as step 0 of all `run_*.sh` scripts behind `TIER_E_SKIP_DOCTOR=1` escape.

**Acceptance:** running doctor on this host reproduces every journalized finding with
actionable text; runners refuse early with the same text when prerequisites vanish.
