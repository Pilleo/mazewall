---
title: "Improve testability to reach 80% coverage for kernel and FFM modules"
scope: "all"
status: "open"
priority: 9
severity: "HIGH"
---

### Context
Coverage for `enforcer` is around 58% and `profiler` is around 40%. The goal is to reach 80% coverage for both modules. However, writing unit tests for `io.mazewall.ffi.networking`, `io.mazewall.ffi.memory`, `io.mazewall.landlock.Landlock`, and `io.mazewall.profiler.engine` is highly coupled with Java FFM `Arena`, `MemorySegment`, and complex mocking using `MockNativeEngine`.

**Needed:**
* Refactor `Landlock.kt` to allow injecting responses instead of heavily relying on nested `NativeTransaction` and private interfaces which block direct testing of `OpenResult.Error`.
* Refactor `MockNativeEngine` to support robust mocking of `FileDescriptor`, `Tid`, `processVmReadv`, and nested type variables without throwing `Unresolved reference` compilation errors in tests.
* Ensure `Mockito` or `MockK` can mock memory allocations.
* Exclude unmockable kernel-heavy classes from Jacoco verification.

**Target:** `docs/internals/backlog/testing`
