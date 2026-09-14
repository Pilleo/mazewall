---
document_type: execution_plan
title: "API Surface and Native Dependency-Inversion Implementation Plan"
base_revision: 26acfaac3b4bb9ec88778a8d815a384d22a12c44
---

# API Surface and Native Dependency-Inversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the supported Kotlin ABI and remove direct native-global and arena construction from application components.

**Architecture:** BCV records the retained ABI of `:platform`, `:enforcer`, and `:profiler`. Runtime factories own `LinuxNative.snapshot()`; application services receive `NativeEngine` or `NativeArena`, enabling fake-engine host tests while FFI resource wrappers retain explicit arena ownership.

**Tech Stack:** Kotlin/JVM 25, Gradle, kotlinx Binary Compatibility Validator, ArchUnit, ast-grep, Codanna.

**Spec:** `docs/superpowers/specs/2026-09-14-api-surface-and-native-dip-design.md`

## Global Constraints

- Preserve public application façades, native ABI, descriptor ownership, errno capture, and Landlock-before-Seccomp ordering.
- Do not add an artifact or dependency other than BCV without explicit approval.
- Do not move FFI ownership primitives behind a longer-lived implicit arena.
- Add a failing host-side test before every production behavior change.

---

### Task 1: Establish the ABI baseline

**Files:**
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `platform/build.gradle.kts`, `enforcer/build.gradle.kts`, `profiler/build.gradle.kts`
- Create: `platform/api/*.api`, `enforcer/api/*.api`, `profiler/api/*.api`
- Test: `platform/src/test/kotlin/io/mazewall/ApiSurfaceTest.kt`

**Interfaces:**
- Produces source-controlled BCV dumps for each published module.
- Produces an explicit allow-list test for application façade packages.

- [ ] **Step 1: Write a failing API-facade test**

```kotlin
@Test
fun `supported platform ABI excludes ffi implementation packages`() {
    assertFalse(readApiDump().contains("io.mazewall.ffi.internal"))
}
```

- [ ] **Step 2: Run it to prove the baseline is absent**

Run: `./gradlew :platform:test --tests io.mazewall.ApiSurfaceTest`

- [ ] **Step 3: Add BCV and generate reviewed dumps**

```kotlin
plugins { alias(libs.plugins.binaryCompatibilityValidator) }
apiValidation { ignoredPackages += listOf("io.mazewall.ffi.internal") }
```

- [ ] **Step 4: Verify the ABI gate**

Run: `./gradlew apiDump apiCheck :platform:test --tests io.mazewall.ApiSurfaceTest`

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle platform/api enforcer/api profiler/api platform/src/test
git commit -m "build: baseline supported Kotlin API"
```

### Task 2: Internalize implementation-only API without façade breakage

**Files:**
- Modify: `platform/src/main/kotlin/io/mazewall/ffi/internal/*.kt`, `platform/src/main/kotlin/io/mazewall/ffi/networking/*.kt`, affected public signatures
- Test: `platform/src/test/kotlin/io/mazewall/ApiSurfaceTest.kt`

**Interfaces:**
- Consumes: reviewed BCV dumps from Task 1.
- Produces: no `ffi.internal` or concrete native implementation type in public ABI.

- [ ] **Step 1: Write a failing ABI assertion for one leaked implementation type**

```kotlin
@Test
fun `real native engine implementation is not exported`() {
    assertFalse(readApiDump().contains("RealNativeEngine"))
}
```

- [ ] **Step 2: Run the focused test and confirm the leak**

Run: `./gradlew :platform:test --tests io.mazewall.ApiSurfaceTest`

- [ ] **Step 3: Replace public concrete signatures with `NativeEngine` traits and mark implementations internal**

```kotlin
internal class RealNativeEngine(...) : NativeEngine
public fun runtimeEngine(): NativeEngine = LinuxNative.snapshot()
```

- [ ] **Step 4: Verify retained ABI and callers**

Run: `./gradlew :platform:test apiCheck`

- [ ] **Step 5: Commit**

```bash
git add platform/src/main platform/src/test platform/api
git commit -m "refactor: hide native implementation ABI"
```

### Task 3: Inject profiler native runtime dependencies

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/ProfilerRuntime.kt`, `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerSocket.kt`, `profiler/src/main/kotlin/io/mazewall/profiler/internal/NativeSocketInputStream.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/ProfilerRuntimeTest.kt`, `profiler/src/test/kotlin/io/mazewall/profiler/internal/ProfilerSocketTest.kt`

**Interfaces:**
- Consumes: `NativeEngine` and `NativeArena`.
- Produces: injectable constructors/functions; `ProfilerRuntime.default()` remains the sole profiler runtime factory that snapshots Linux native state.

- [ ] **Step 1: Write failing fake-engine tests**

```kotlin
@Test
fun `socket connection uses supplied native engine`() {
    val engine = MockNativeEngine()
    connectDescriptorWithRetry(path, engine, maxRetries = 1, delayMs = 0)
    assertEquals(1, engine.networking.connectCalls)
}
```

- [ ] **Step 2: Run the focused test and confirm it currently cannot inject the engine**

Run: `./gradlew :profiler:test --tests io.mazewall.profiler.internal.ProfilerSocketTest`

- [ ] **Step 3: Thread engine and scope explicitly**

```kotlin
internal fun connectDescriptorWithRetry(path: Path, engine: NativeEngine, maxRetries: Int, delayMs: Long) = ...
internal class NativeSocketInputStream(fd: OpenSocket, arena: NativeArena, memory: NativeMemory)
```

- [ ] **Step 4: Verify host tests and structural absence**

Run: `ast-grep run --lang kotlin --pattern 'LinuxNative.snapshot()' profiler/src/main/kotlin`
Run: `./gradlew :profiler:test`

- [ ] **Step 5: Commit**

```bash
git add profiler/src/main profiler/src/test profiler/api
git commit -m "refactor: inject profiler native dependencies"
```

### Task 4: Replace application-level confined arena allocation

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/ffi/ProfilerNativeCommandWriter.kt`, `profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/ffi/ProfilerNativeCommandWriterTest.kt`, `profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngineTest.kt`

**Interfaces:**
- Consumes: `NativeArena` supplied by a scoped callback or constructor dependency.
- Produces: no direct `Arena.ofConfined()` outside `NativeArena` and FFI primitives.

- [ ] **Step 1: Write a failing fake-scope lifecycle test**

```kotlin
@Test
fun `command writer closes its supplied native scope after encoding`() {
    val arena = RecordingNativeArena()
    writer.write(command, arena)
    assertTrue(arena.closed)
}
```

- [ ] **Step 2: Run the focused test and observe the missing injected scope path**

Run: `./gradlew :profiler:test --tests io.mazewall.profiler.ffi.ProfilerNativeCommandWriterTest`

- [ ] **Step 3: Use `NativeArena.ofConfined()` at the explicit resource boundary**

```kotlin
NativeArena.ofConfined().use { arena -> encode(command, arena) }
```

- [ ] **Step 4: Verify all direct arena use is confined to the allowed abstraction**

Run: `ast-grep run --lang kotlin --pattern 'Arena.ofConfined()' profiler/src/main/kotlin`
Run: `./gradlew :profiler:test`

- [ ] **Step 5: Commit**

```bash
git add profiler/src/main profiler/src/test profiler/api
git commit -m "refactor: isolate profiler native scopes"
```

### Task 5: Audit, document, and resolve both backlog items

**Files:**
- Modify: `docs/internals/designs/core/architectural-map.md`, `docs/internals/designs/core/testing-strategy.md`, `docs/internals/backlog/README.md`
- Move: `docs/internals/backlog/code_health/issue-20260808-032525-reduce-and-version-public-api-surface.md` → `docs/internals/backlog/resolved/`
- Move: `docs/internals/backlog/testing/issue-030-architectural-dip-dependency-inversion-violations-in-native-.md` → `docs/internals/backlog/resolved/`

- [ ] **Step 1: Re-run Codanna and ast-grep evidence**

Run: `codanna index .`
Run: `./scripts/adkw blast-radius NativeEngine`

- [ ] **Step 2: Update architecture and testing documentation with retained public façades and injection boundaries**

- [ ] **Step 3: Run delivery verification**

Run: `./gradlew build`
Run: `./scripts/adkw check-backlog`

- [ ] **Step 4: Resolve only after evidence passes**

```bash
git mv docs/internals/backlog/code_health/issue-20260808-032525-reduce-and-version-public-api-surface.md docs/internals/backlog/resolved/
git mv docs/internals/backlog/testing/issue-030-architectural-dip-dependency-inversion-violations-in-native-.md docs/internals/backlog/resolved/
```

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "docs: record supported API and native injection boundaries"
```
