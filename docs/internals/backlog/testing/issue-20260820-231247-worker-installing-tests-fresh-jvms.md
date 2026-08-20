# Worker-Installing Tests in Fresh JVMs

**Source:** Codex PR review comment 3797199300  
**Priority:** P2  
**Status:** Backlogged  
**Created:** 2026-08-20

## Problem

In the enforcer integration pipeline, `forkEvery=0` now shares one worker among all untagged tests. The test `IntelCetIntegrationTest.queryIntelCetStatus returns active status when CET is supported and locked` remains untagged and calls `policy.install()` directly on that JUnit worker.

On CET-enabled hosts, the irreversible seccomp filter persists into later tests and can deny executable mappings or otherwise contaminate results.

## Impact

Test contamination: State from one test (installed seccomp filters) affects subsequent tests running in the same JVM, leading to:
- False test failures
- Unpredictable test results
- Difficult to debug test environment issues

## Solution

Tag every such test with `@Isolated` or similar annotation before enabling worker reuse. The test should be annotated to ensure it runs in a fresh JVM, preventing state leakage to other tests.

## Related Files

- `enforcer/src/test/kotlin/io/mazewall/IntelCetIntegrationTest.kt` - Needs `@Isolated` annotation
- Test configuration for forkEvery settings

## Notes

This is a test infrastructure issue. The fix is straightforward: add proper isolation annotations to tests that modify global state (like installing seccomp filters).
