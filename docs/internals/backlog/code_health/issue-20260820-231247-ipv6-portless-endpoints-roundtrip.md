---
title: "Preserve portless IPv6 endpoints during JSON round trips"
severity: "MEDIUM"
status: "open"
priority: medium
component: "profiler"
dependencies: []
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehaviorDto.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfileObservation.kt"
effort: "small"
autonomy: "autonomous"
---

# Preserve Portless IPv6 Endpoints During JSON Round Trips

**Source:** Codex PR review comment 3797199306  
**Priority:** P2  
**Status:** Backlogged  
**Created:** 2026-08-20

## Problem

When an eBPF `kind=connect` event supplies an IPv6 host but omits the optional port, serialization writes a bare value such as `2001:db8::1`; this parser then interprets the final numeric segment as port `1` and reloads the host as `2001:db8:` (truncated).

## Impact

IPv6 endpoints without explicit ports are corrupted during serialization and deserialization, leading to:
- Incorrect endpoint information in Bills of Behavior
- Failed connection attempts when policies are enforced
- Inability to properly identify network destinations

## Solution

Persist host and port as separate fields, or use an unambiguous bracketed encoding, so saving and loading a Bill of Behavior does not corrupt endpoint identity.

Options:
1. Separate host and port into distinct JSON fields
2. Use RFC 3986 style bracketed IPv6 addresses: `[2001:db8::1]:80`
3. Use a wrapper object for endpoints that explicitly tracks host and port

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehaviorDto.kt` - JSON serialization
- `profiler/src/main/kotlin/io/mazewall/profiler/ProfileObservation.kt` - Connect observation
- `profiler/src/main/kotlin/io/mazewall/profiler/ConnectEndpoint.kt` (if exists) - Endpoint representation

## Notes

This is a data serialization bug. The current format loses information when ports are omitted, and the parser makes incorrect assumptions about the structure of IPv6 addresses.
