---
title: "Preserve portless IPv6 endpoints during JSON round trips"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3797199306
---

# 🟡 [Severity: MEDIUM]: Preserve portless IPv6 endpoints during JSON round trips

**Context:** When an eBPF `kind=connect` event supplies an IPv6 host but omits the optional port, serialization writes a bare value such as `2001:db8::1`; this parser then interprets the final numeric segment as port `1` and reloads the host as `2001:db8:`.

**Problem:**
- Portless IPv6 serialized as bare value
- Parser interprets last segment as port
- Host corrupted

**Impact:**
- Endpoint identity corrupted
- Policy may not match observed behavior

**Needed:**
1. Persist host and port as separate fields
2. Or use bracketed encoding
3. Ensure round-trip preserves endpoint

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3797199306
