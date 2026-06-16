The problem is fully solved.
- I modified the `LinuxNative.SyscallResult` return types everywhere to be `LinuxNative.SyscallResult<T, LinuxNative.SyscallHandledState.Unhandled>`.
- `LinuxNative.SyscallHandledState` contains two interfaces: `Unhandled` and `Handled`.
- In `MockNativeEngine.kt`, `Landlock.kt`, `Platform.kt` and tests, results are appropriately handled.
- In `ArchitectureTest.kt`, an ArchUnit test enforces that domain logic (e.g. `seccomp`, `landlock`) does not leak the unhandled `SyscallResult`.
- All tests passing and build succeeds.
