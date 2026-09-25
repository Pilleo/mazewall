package io.mazewall.profiler.testing.tierE

import io.mazewall.testing.awaitCondition
import io.mazewall.testing.containers.ContainerCommandResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TierENativeFixtureBuildTest {
    @Test
    fun `separate checkouts never share mutable native CMake cache`() {
        val dir = Files.createTempDirectory("tier-e-native-identity-")
        try {
            val first = Files.createDirectories(dir.resolve("first/profiler-native"))
            val second = Files.createDirectories(dir.resolve("second/profiler-native"))
            val a = TierENativeFixtureBuild.cacheDirectory(first.parent, "Release", "clang-18", "x86_64", "usdt=false")
            val b = TierENativeFixtureBuild.cacheDirectory(second.parent, "Release", "clang-18", "x86_64", "usdt=false")
            assertTrue(a != b)
            assertEquals(a, TierENativeFixtureBuild.cacheDirectory(first.parent, "Release", "clang-18", "x86_64", "usdt=false"))
            assertTrue(a != TierENativeFixtureBuild.cacheDirectory(first.parent, "Debug", "clang-18", "x86_64", "usdt=false"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `explicit cache configured for another checkout is rejected without rebuilding`() {
        val root = Files.createTempDirectory("tier-e-native-foreign-")
        try {
            Files.createDirectories(root.resolve("profiler-native"))
            val compiler = Files.createFile(root.resolve("fake-cxx")).toString()
            val cache = Files.createDirectories(root.resolve("cache"))
            Files.writeString(
                cache.resolve("CMakeCache.txt"),
                "CMAKE_HOME_DIRECTORY:INTERNAL=/someone/else/profiler-native\nCMAKE_BUILD_TYPE:STRING=Release\n",
            )
            val calls = mutableListOf<List<String>>()
            val error = assertThrows(IllegalStateException::class.java) {
                TierENativeFixtureBuild.prepare(root, cache, "Release", compiler, "x86_64", "usdt=false") { command, _ ->
                    calls += command
                    ContainerCommandResult(0, "unexpected")
                }
            }
            assertTrue(error.message!!.contains("CMAKE_HOME_DIRECTORY"))
            assertTrue(calls.isEmpty(), "foreign cache cannot run any build command")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `same checkout reuses configuration but incrementally rebuilds source edits`() {
        val root = Files.createTempDirectory("tier-e-native-reuse-")
        try {
            val source = Files.createDirectories(root.resolve("profiler-native"))
            val compiler = Files.createFile(root.resolve("fake-cxx")).toString()
            val input = source.resolve("fixture.cpp")
            Files.writeString(input, "first revision")
            val output = TierENativeFixtureBuild.cacheDirectory(root, "Release", compiler, "x86_64", "usdt=false")
            var configs = 0
            var builds = 0
            val runner: (List<String>, Duration) -> ContainerCommandResult = { command, _ ->
                if ("-S" in command) {
                    configs++
                    Files.createDirectories(output)
                    Files.writeString(
                        output.resolve("CMakeCache.txt"),
                        "CMAKE_HOME_DIRECTORY:INTERNAL=${source.toRealPath()}\nCMAKE_BUILD_TYPE:STRING=Release\n" +
                            "CMAKE_CXX_COMPILER:FILEPATH=$compiler\nMAZEWALL_ENABLE_TIER_E_USDT:BOOL=OFF\n",
                    )
                } else {
                    builds++
                    Files.writeString(output.resolve("libmazewall_stack_agent.so"), Files.readString(input))
                    Files.createDirectories(output.resolve("agent-test-classes"))
                    Files.writeString(output.resolve("agent-test-classes/AgentCoverageWorkload.class"), "fixture")
                }
                ContainerCommandResult(0, "")
            }
            TierENativeFixtureBuild.prepare(root, output, "Release", compiler, "x86_64", "usdt=false", runner)
            Files.writeString(input, "second revision")
            TierENativeFixtureBuild.prepare(root, output, "Release", compiler, "x86_64", "usdt=false", runner)
            assertEquals(1, configs)
            assertEquals(2, builds)
            assertEquals("second revision", Files.readString(output.resolve("libmazewall_stack_agent.so")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native cache refuses an altered configured compiler or USDT option`() {
        for ((field, altered) in listOf(
            "CMAKE_CXX_COMPILER:FILEPATH" to "/foreign/compiler",
            "MAZEWALL_ENABLE_TIER_E_USDT:BOOL" to "ON",
        )) {
            val root = Files.createTempDirectory("tier-e-native-altered-")
            try {
                val source = Files.createDirectories(root.resolve("profiler-native"))
                val compiler = Files.createFile(root.resolve("fake-cxx")).toString()
                val directory = root.resolve("cache")
                var builds = 0
                val runner: (List<String>, Duration) -> ContainerCommandResult = { command, _ ->
                    if ("-S" in command) {
                        assertTrue(command.contains("-DCMAKE_CXX_COMPILER=$compiler"))
                        Files.writeString(
                            directory.resolve("CMakeCache.txt"),
                            "CMAKE_HOME_DIRECTORY:INTERNAL=${source.toRealPath()}\n" +
                                "CMAKE_BUILD_TYPE:STRING=Release\n" +
                                "CMAKE_CXX_COMPILER:FILEPATH=$compiler\n" +
                                "MAZEWALL_ENABLE_TIER_E_USDT:BOOL=OFF\n",
                        )
                    } else {
                        builds++
                        Files.writeString(directory.resolve("libmazewall_stack_agent.so"), "agent")
                        Files.createDirectories(directory.resolve("agent-test-classes"))
                        Files.writeString(directory.resolve("agent-test-classes/AgentCoverageWorkload.class"), "workload")
                    }
                    ContainerCommandResult(0, "")
                }
                TierENativeFixtureBuild.prepare(root, directory, "Release", compiler, "x86_64", "usdt=false", runner)
                val cache = directory.resolve("CMakeCache.txt")
                Files.writeString(cache, Files.readString(cache).replace(Regex("(?m)^${Regex.escape(field)}=.*$"), "$field=$altered"))
                val failure = assertThrows(IllegalStateException::class.java) {
                    TierENativeFixtureBuild.prepare(root, directory, "Release", compiler, "x86_64", "usdt=false", runner)
                }
                assertTrue(failure.message!!.contains(field.substringBefore(':')), failure.message)
                assertEquals(1, builds, "altered native configuration must not rebuild")
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `validated default cache can recover after configure ended before marker publication`() {
        val root = Files.createTempDirectory("tier-e-native-partial-")
        try {
            val source = Files.createDirectories(root.resolve("profiler-native"))
            val compiler = Files.createFile(root.resolve("fake-cxx")).toString()
            val directory = TierENativeFixtureBuild.cacheDirectory(root, "Release", compiler, "x86_64", "usdt=false")
            Files.createDirectories(directory)
            Files.writeString(
                directory.resolve("CMakeCache.txt"),
                "CMAKE_HOME_DIRECTORY:INTERNAL=${source.toRealPath()}\n" +
                    "CMAKE_BUILD_TYPE:STRING=Release\nCMAKE_CXX_COMPILER:FILEPATH=$compiler\n" +
                    "MAZEWALL_ENABLE_TIER_E_USDT:BOOL=false\n",
            )
            val output = TierENativeFixtureBuild.prepare(
                root,
                buildType = "Release",
                compiler = compiler,
                architecture = "x86_64",
                flags = "usdt=false",
            ) { command, _ ->
                assertTrue("--build" in command, "matching CMake cache should not require reconfigure")
                Files.writeString(directory.resolve("libmazewall_stack_agent.so"), "agent")
                Files.createDirectories(directory.resolve("agent-test-classes"))
                Files.writeString(directory.resolve("agent-test-classes/AgentCoverageWorkload.class"), "workload")
                ContainerCommandResult(0, "")
            }
            assertEquals(directory, output)
            assertTrue(Files.exists(directory.resolve(".mazewall-native-identity")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parallel preparation serializes writes to the same native cache`() {
        val root = Files.createTempDirectory("tier-e-native-serial-")
        try {
            val source = Files.createDirectories(root.resolve("profiler-native"))
            val compiler = Files.createFile(root.resolve("fake-cxx")).toString()
            val directory = TierENativeFixtureBuild.cacheDirectory(root, "Release", compiler, "x86_64", "usdt=false")
            val buildStarted = CountDownLatch(1)
            val releaseFirstBuild = CountDownLatch(1)
            val secondAttempted = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val completed = AtomicInteger()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val runner: (List<String>, Duration) -> ContainerCommandResult = { command, _ ->
                if ("-S" in command) {
                    Files.writeString(
                        directory.resolve("CMakeCache.txt"),
                        "CMAKE_HOME_DIRECTORY:INTERNAL=${source.toRealPath()}\nCMAKE_BUILD_TYPE:STRING=Release\n" +
                            "CMAKE_CXX_COMPILER:FILEPATH=$compiler\nMAZEWALL_ENABLE_TIER_E_USDT:BOOL=OFF\n",
                    )
                } else {
                    val now = active.incrementAndGet()
                    maximum.accumulateAndGet(now, ::maxOf)
                    buildStarted.countDown()
                    if (now == 1) assertTrue(releaseFirstBuild.await(5, TimeUnit.SECONDS))
                    Files.writeString(directory.resolve("libmazewall_stack_agent.so"), "agent")
                    Files.createDirectories(directory.resolve("agent-test-classes"))
                    Files.writeString(directory.resolve("agent-test-classes/AgentCoverageWorkload.class"), "workload")
                    completed.incrementAndGet()
                    active.decrementAndGet()
                }
                ContainerCommandResult(0, "")
            }

            fun prepare() = TierENativeFixtureBuild.prepare(root, directory, "Release", compiler, "x86_64", "usdt=false", runner)
            val first = Thread {
                try {
                prepare()
            } catch (failure: Throwable) {
                failures += failure
            }
            }
            val second = Thread {
                secondAttempted.countDown()
                try {
                    prepare()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            try {
                first.start()
                assertTrue(buildStarted.await(5, TimeUnit.SECONDS))
                second.start()
                assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
                awaitCondition(Duration.ofSeconds(2), "second builder waits for owned cache") {
                    second.state in setOf(Thread.State.WAITING, Thread.State.BLOCKED, Thread.State.TIMED_WAITING) ||
                        maximum.get() > 1 ||
                        failures.isNotEmpty()
                }
            } finally {
                releaseFirstBuild.countDown()
                first.join(5_000)
                if (second.state != Thread.State.NEW) second.join(5_000)
            }
            assertTrue(!first.isAlive && !second.isAlive)
            assertTrue(failures.isEmpty(), "parallel builder failed: ${failures.joinToString { it.toString() }}")
            assertEquals(2, completed.get(), "both preparations must complete successfully")
            assertEquals(1, maximum.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
