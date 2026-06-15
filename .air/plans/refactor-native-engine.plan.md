# Plan: Refactor `LinuxNative` and `RealNativeEngine` for Interface Segregation

This plan addresses the "Interface Segregation Violation and Fat Class Smell in `LinuxNative` / `RealNativeEngine`" issue by modularizing the monolithic native engine into focused sub-engines.

## Goal
Decouple `NativeEngine` from the monolithic inheritance hierarchy and refactor `RealNativeEngine` and `LinuxNative` to delegate to modular sub-engines (FileSystem, Networking, Process, Memory).

## Approach
1.  **Decompose `NativeEngine`**: Remove inheritance from sub-interfaces and add them as properties to `NativeEngine`.
2.  **Modularize `RealNativeEngine`**: Split the monolithic implementation into separate internal objects, each implementing a single sub-interface.
3.  **Refactor `LinuxNative`**: Update `LinuxNative` to delegate to these modular sub-engines and remove its direct inheritance from sub-interfaces to promote modularity.
4.  **Update `MockNativeEngine`**: Allow composing mocks from individual sub-engine mocks, simplifying unit tests.

## File Changes

### 1. `enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt`
- **Modify**: `NativeEngine` interface:
    - Remove `: NativeFileSystem, NativeNetworking, NativeProcess, NativeMemory`.
    - Add properties: `val fileSystem: NativeFileSystem`, `val networking: NativeNetworking`, `val process: NativeProcess`, `val memory: NativeMemory`.
    - Keep core methods: `syscall`, `syscall4`, `ioctl`, `fcntl`, `poll`.

### 2. `enforcer/src/main/kotlin/io/mazewall/LinuxNative.kt`
- **Modify**: `LinuxNative` object:
    - Remove inheritance from `NativeFileSystem`, `NativeNetworking`, `NativeProcess`, `NativeMemory`.
    - Implement new `NativeEngine` properties by delegating to the internal `engine`.
    - Keep `getFileSystem()`, `getNetworking()`, etc., but update them to use `engine.fileSystem`, etc.
    - Remove monolithic delegates (e.g., `open`, `socket`, `bind`).
- **Modify**: `RealNativeEngine` internal object:
    - Implement `NativeEngine` by providing modular internal sub-engines.
    - Extract methods and `MethodHandle`s into `RealNativeFileSystem`, `RealNativeNetworking`, `RealNativeProcess`, and `RealNativeMemory`.

### 3. `src/sharedTest/kotlin/io/mazewall/MockNativeEngine.kt`
- **Modify**: `MockNativeEngine` class:
    - Implement new `NativeEngine` properties with default mock sub-engines.
    - Support passing custom mock sub-engines via constructor.
- **Create**: `MockNativeFileSystem`, `MockNativeNetworking`, `MockNativeProcess`, and `MockNativeMemory` classes.

## Implementation Steps

### Task 1: Redefine `NativeEngine` Interface
1.  Update [NativeEngine.kt](air-file://btnc84n2s3o812v3vg7m/home/leanid/Documents/code/java/jseccomp/enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt) to restructure `NativeEngine` as a container of sub-engines.

### Task 2: Decompose `RealNativeEngine`
1.  In [LinuxNative.kt](air-file://btnc84n2s3o812v3vg7m/home/leanid/Documents/code/java/jseccomp/enforcer/src/main/kotlin/io/mazewall/LinuxNative.kt):
    - Create `RealNativeFileSystem` implementing `NativeFileSystem`.
    - Create `RealNativeNetworking` implementing `NativeNetworking`.
    - Create `RealNativeProcess` implementing `NativeProcess`.
    - Create `RealNativeMemory` implementing `NativeMemory`.
    - Move corresponding `MethodHandle`s and method implementations from `RealNativeEngine` to these sub-objects.
    - Update `RealNativeEngine` to provide these sub-objects via its properties.

### Task 3: Refactor `LinuxNative` Entry Point
1.  In [LinuxNative.kt](air-file://btnc84n2s3o812v3vg7m/home/leanid/Documents/code/java/jseccomp/enforcer/src/main/kotlin/io/mazewall/LinuxNative.kt):
    - Remove sub-interface inheritance from `LinuxNative`.
    - Implement `fileSystem`, `networking`, `process`, and `memory` properties by delegating to `engine`.
    - Update `getFileSystem()`, `getNetworking()`, etc., to use these properties.
    - Remove monolithic methods like `open()`, `socket()`, `bind()`, etc.

### Task 4: Modularize `MockNativeEngine`
1.  Update [MockNativeEngine.kt](air-file://btnc84n2s3o812v3vg7m/home/leanid/Documents/code/java/jseccomp/src/sharedTest/kotlin/io/mazewall/MockNativeEngine.kt):
    - Define `MockNativeFileSystem`, `MockNativeNetworking`, `MockNativeProcess`, and `MockNativeMemory`.
    - Update `MockNativeEngine` to be composed of these sub-mocks.

### Task 5: Fix Call Sites and Tests
1.  Perform a global search and replace to update all call sites:
    - `LinuxNative.open(...)` -> `LinuxNative.getFileSystem().open(...)`
    - `LinuxNative.close(...)` -> `LinuxNative.getFileSystem().close(...)`
    - `LinuxNative.socket(...)` -> `LinuxNative.getNetworking().socket(...)`
    - `LinuxNative.bind(...)` -> `LinuxNative.getNetworking().bind(...)`
    - `LinuxNative.listen(...)` -> `LinuxNative.getNetworking().listen(...)`
    - `LinuxNative.accept(...)` -> `LinuxNative.getNetworking().accept(...)`
    - `LinuxNative.connect(...)` -> `LinuxNative.getNetworking().connect(...)`
    - `LinuxNative.sendmsg(...)` -> `LinuxNative.getNetworking().sendmsg(...)`
    - `LinuxNative.recvmsg(...)` -> `LinuxNative.getNetworking().recvmsg(...)`
    - `LinuxNative.recv(...)` -> `LinuxNative.getNetworking().recv(...)`
    - `LinuxNative.socketpair(...)` -> `LinuxNative.getNetworking().socketpair(...)`
    - `LinuxNative.readlink(...)` -> `LinuxNative.getFileSystem().readlink(...)`
    - `LinuxNative.read(...)` -> `LinuxNative.getMemory().read(...)`
    - `LinuxNative.write(...)` -> `LinuxNative.getMemory().write(...)`
    - `LinuxNative.processVmReadv(...)` -> `LinuxNative.getMemory().processVmReadv(...)`
    - `LinuxNative.newSockFProg(...)` -> `LinuxNative.getMemory().newSockFProg(...)`
2.  Update tests that directly set mock results:
    - `mock.openResult = ...` -> `mock.fileSystem.openResult = ...`
    - `mock.syscallResult = ...` (stays same as `syscall` is still in `NativeEngine`)

## Acceptance Criteria
- [ ] `NativeEngine` is no longer a "fat" interface inheriting from all sub-interfaces.
- [ ] `RealNativeEngine` implementation is split into modular sub-engine objects.
- [ ] `LinuxNative` object no longer implements sub-interfaces directly.
- [ ] `MockNativeEngine` allows granular mocking of specific sub-interfaces.
- [ ] All unit and integration tests pass.
- [ ] Code coverage for native engine components remains above 78%.

## Verification Steps
1.  Run `:enforcer:test` to verify core logic and mocks.
2.  Run `:enforcer:integrationTest` to verify real FFM interactions.
3.  Run `:profiler:test` and `:profiler:integrationTest` to ensure no regressions in profiler usage of native engines.
4.  Execute `./scripts/run_tests.sh` for full suite verification.

## Risks & Mitigations
- **Breaking API Change**: External users of `mazewall` (though mostly internal now) might rely on `LinuxNative.open`. *Mitigation:* `LinuxNative` is intended for internal library use, but the change is necessary for long-term maintainability.
- **Initialization Order**: Moving `MethodHandle`s to sub-objects might affect `LayoutValidator.validate()` call. *Mitigation:* Ensure `LayoutValidator.validate()` is still called appropriately during initialization of the sub-engines or `RealNativeEngine`.
