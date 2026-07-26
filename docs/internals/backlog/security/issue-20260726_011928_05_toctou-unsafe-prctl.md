---
title: Unsafe prctl TOCTOU vulnerability documented but not properly prevented
type: issue
status: open
priority: 5
labels:
- security
- enforcer
- toctou
- documentation
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/seccomp/UnsafePrctlInspector.kt
---

# Issue: TOCTOU in Unsafe Prctl Policy Options

## Context
The `allowUnsafePrctl` option in the policy explicitly permits dangerous `prctl` calls that take pointer arguments, which are inherently vulnerable to TOCTOU.

## The Bug
While this vulnerability is documented in multiple places, the design should ensure that developers are explicitly warned when compiling a policy that uses this flag, perhaps by requiring a loud acknowledgment (e.g., throwing a compile-time error or runtime warning if used outside of tests).

## Security / Stability Impact
- **Sandbox Bypass via TOCTOU**: Attackers can race the kernel by modifying memory referenced by `prctl` after the seccomp filter check.

## Recommendation
Log a severe warning to standard out when `allowUnsafePrctl` is used during `CompiledSandbox` creation.
