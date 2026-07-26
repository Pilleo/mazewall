---
title: "Redundant BPF Argument Inspection Blocks in Stacked Filters cause performance and size bloat"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Redundant BPF Argument Inspection Blocks in Stacked Filters cause performance and size bloat

**Status:** RESOLVED (June 2026)
**Target:** `/enforcer/src/main/kotlin/io/mazewall/enforcer/FilterInstallationPlanner.kt` (specifically `calculateNewFilter`)
**Context:** Seccomp BPF filters are additive. If a previous filter already restricts `mmap(PROT_EXEC)`, non-thread `clone`, or unsafe `prctl` calls, there is no need to compile and install duplicate argument inspection blocks for these syscalls in a new stacked filter.
**Fix:** Optimized `FilterInstallationPlanner.calculateNewFilter` to skip redundant inspection blocks if already enforced in the current thread state.
