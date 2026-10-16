package io.mazewall.profiler.tierE.tools.controller

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.runContainerCommand
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Checkout-bound incremental CMake build for the rootful Tier E fixture. */
public object TierENativeFixtureBuild {
    private val locks = ConcurrentHashMap<Path, ReentrantLock>()

    public fun cacheDirectory(
        root: Path,
        buildType: String,
        compiler: String,
        architecture: String,
        flags: String,
    ): Path {
        val identity = "${root.toRealPath()}/profiler-native\n$buildType\n$compiler\n$architecture\n$flags"
        val hash = MessageDigest
            .getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return root.toRealPath().resolve("build/tier-e-native/$hash")
    }

    public fun prepare(
        root: Path,
        explicit: Path? = null,
        buildType: String = "Release",
        compiler: String = "c++",
        architecture: String = System.getProperty("os.arch"),
        flags: String = "usdt=false",
        execute: (List<String>, Duration) -> ContainerCommandResult = { command, timeout -> runContainerCommand(command, timeout) },
    ): Path {
        require(buildType.matches(Regex("[A-Za-z0-9_]+")))
        val source = root.resolve("profiler-native").toRealPath()
        val directory = (explicit ?: cacheDirectory(root, buildType, compiler, architecture, flags)).toAbsolutePath().normalize()
        Files.createDirectories(directory)
        val identity = "${source}\n$buildType\n$compiler\n$architecture\n$flags\n"
        val requestedCompiler = Path.of(compiler)
        val compilerPath = if (requestedCompiler.isAbsolute) {
            requestedCompiler
        } else {
            (System.getenv("PATH") ?: "")
                .split(':')
                .asSequence()
                .map { Path.of(it).resolve(compiler) }
                .firstOrNull(Files::isExecutable)
                ?: error("Native fixture compiler unavailable: $compiler")
        }
        val expectedCompiler = compilerPath.toRealPath()
        val usdt = flags.substringBefore(';') == "usdt=true"

        fun validate(cache: Path) {
            val entries = Files
                .readAllLines(cache)
                .mapNotNull { line ->
                val pair = line.split('=', limit = 2)
                if (pair.size == 2) pair[0].substringBefore(':') to pair[1] else null
            }.toMap()
            check(entries["CMAKE_HOME_DIRECTORY"] == source.toString()) {
                "CMAKE_HOME_DIRECTORY disagrees with $source in $cache"
            }
            check(entries["CMAKE_BUILD_TYPE"] == buildType) { "CMAKE_BUILD_TYPE disagrees with $buildType in $cache" }
            val configured = entries["CMAKE_CXX_COMPILER"]?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }
            check(configured == expectedCompiler) { "CMAKE_CXX_COMPILER disagrees with $expectedCompiler in $cache" }
            val configuredUsdt = entries["MAZEWALL_ENABLE_TIER_E_USDT"]?.uppercase()
            check(configuredUsdt in if (usdt) setOf("ON", "TRUE", "YES", "1") else setOf("OFF", "FALSE", "NO", "0")) {
                "MAZEWALL_ENABLE_TIER_E_USDT disagrees with $usdt in $cache"
            }
        }
        locks.computeIfAbsent(directory) { ReentrantLock() }.withLock {
            FileChannel.open(directory.resolve(".build.lock"), CREATE, WRITE).use { channel ->
                channel.lock().use {
                    val cache = directory.resolve("CMakeCache.txt")
                    val marker = directory.resolve(".mazewall-native-identity")
                    if (Files.exists(cache)) {
                        validate(cache)
                        // Configure can complete before the marker is published. Only a
                        // computed default cache with verified CMake settings may be adopted.
                        if (!Files.exists(marker) &&
                            explicit == null &&
                            directory == cacheDirectory(root, buildType, compiler, architecture, flags)
                        ) {
                            Files.writeString(marker, identity)
                        }
                        check(Files.exists(marker) && Files.readString(marker) == identity) {
                            "Native CMake cache has no matching checkout/compiler provenance: $directory"
                        }
                    } else {
                        check(!Files.exists(marker)) { "Partial native cache without CMakeCache.txt: $directory" }
                        val configured = execute(
                            listOf(
                                "cmake",
                                "-S",
                                source.toString(),
                                "-B",
                                directory.toString(),
                                "-DCMAKE_BUILD_TYPE=$buildType",
                                "-DCMAKE_CXX_COMPILER=$compiler",
                                "-DMAZEWALL_ENABLE_TIER_E_USDT=$usdt",
                            ),
                            Duration.ofMinutes(2),
                        )
                        check(configured.exitCode == 0) { "Native CMake configure failed: ${configured.output}" }
                        check(Files.exists(cache)) { "Native CMake configure produced no CMakeCache.txt: $directory" }
                        validate(cache)
                        Files.writeString(marker, identity)
                    }
                    val built = execute(listOf("cmake", "--build", directory.toString(), "-j2"), Duration.ofMinutes(2))
                    check(built.exitCode == 0) { "Native fixture build failed: ${built.output}" }
                    check(Files.isRegularFile(directory.resolve("libmazewall_stack_agent.so"))) {
                        "Native fixture agent missing after build: $directory"
                    }
                    check(Files.isRegularFile(directory.resolve("agent-test-classes/AgentCoverageWorkload.class"))) {
                        "Native fixture workload missing after build: $directory"
                    }
                }
            }
        }
        return directory
    }
}
