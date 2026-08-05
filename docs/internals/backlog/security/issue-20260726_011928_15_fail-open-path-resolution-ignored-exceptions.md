---
title: SupervisorSessionHandler ignores critical exceptions during path resolution
  leading to fail-open bypass
type: issue
status: open
priority: 8
labels:
- security
- enforcer
- fail-open
- sandbox-bypass
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt
github_issue: 443
---

# Issue: `SupervisorSessionHandler` fails open on ignored exceptions

## Context
In `SupervisorSessionHandler.kt`, `resolveCanonicalPath` and the static fast-path initializer contain over a dozen `try { ... } catch (ignored: Exception) {}` blocks.

## The Bug
When constructing the `safeBypassPaths` for CI, java agents, or when attempting to resolve `bypassPath.resolve(pathStr).toRealPath()`, exceptions are silently ignored.
If `toRealPath()` throws a `SecurityException`, `IOException` or a structural error, the system ignores it. If the path was meant to be canonicalized to avoid directory traversal (`../`), failing to canonicalize but continuing means the validation logic might fall back to string matching an uncanonicalized string against a policy.
More importantly, if adding java agent jars or CI paths fails silently, those critical files will be blocked, causing mysterious CI deadlocks.
Conversely, if an attacker provides a path that crashes `toRealPath()`, they might be able to exploit the fallback behavior to bypass the checks.

## Recommendation
Do not swallow `Exception` unconditionally during path canonicalization and loading. If an I/O exception occurs, it should be logged or properly handled. If path validation fails, it must fail-closed (return null/deny) instead of falling through to alternative resolvers unhandled.
