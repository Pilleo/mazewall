#!/bin/bash
mv enforcer/src/test/kotlin/io/mazewall/BpfFilterCoverageTest.kt enforcer/src/test/kotlin/io/mazewall/BpfFilterTest.kt
mv enforcer/src/test/kotlin/io/mazewall/LinuxNativeCoverageTest.kt enforcer/src/test/kotlin/io/mazewall/LinuxNativeTest.kt
mv enforcer/src/test/kotlin/io/mazewall/enforcer/ContainedExecutorsCoverageTest.kt enforcer/src/test/kotlin/io/mazewall/enforcer/ContainedExecutorsTest.kt
mv enforcer/src/test/kotlin/io/mazewall/enforcer/SandboxDispatcherCoverageTest.kt enforcer/src/test/kotlin/io/mazewall/enforcer/SandboxDispatcherTest.kt
mv enforcer/src/test/kotlin/io/mazewall/landlock/LandlockCoverageTest.kt enforcer/src/test/kotlin/io/mazewall/landlock/LandlockTest.kt
mv enforcer/src/test/kotlin/io/mazewall/seccomp/BpfBuilderCoverageTest.kt enforcer/src/test/kotlin/io/mazewall/seccomp/BpfBuilderTest.kt
mv profiler/src/test/kotlin/io/mazewall/profiler/ProfilerClassCoverageTest.kt profiler/src/test/kotlin/io/mazewall/profiler/ProfilerClassTest.kt
