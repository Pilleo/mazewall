package io.mazewall.testing.containers

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
        locks.computeIfAbsent(directory) { ReentrantLock() }.withLock {
            FileChannel.open(directory.resolve(".build.lock"), CREATE, WRITE).use { channel ->
                channel.lock().use {
                    val cache = directory.resolve("CMakeCache.txt")
                    val marker = directory.resolve(".mazewall-native-identity")
                    if (Files.exists(cache)) {
                        val content = Files.readString(cache)
                        check(content.lineSequence().any { it == "CMAKE_HOME_DIRECTORY:INTERNAL=$source" }) {
                            "CMAKE_HOME_DIRECTORY disagrees with $source in $cache"
                        }
                        check(content.lineSequence().any { it == "CMAKE_BUILD_TYPE:STRING=$buildType" }) {
                            "CMAKE_BUILD_TYPE disagrees with $buildType in $cache"
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
                                "-DMAZEWALL_ENABLE_TIER_E_USDT=${flags == "usdt=true"}",
                            ),
                                Duration.ofMinutes(2),
                        )
                        check(configured.exitCode == 0) { "Native CMake configure failed: ${configured.output}" }
                        check(Files.exists(cache)) { "Native CMake configure produced no CMakeCache.txt: $directory" }
                        val content = Files.readString(cache)
                        check(content.contains("CMAKE_HOME_DIRECTORY:INTERNAL=$source")) {
                            "CMAKE_HOME_DIRECTORY mismatch after configure: $directory"
                        }
                        check(content.contains("CMAKE_BUILD_TYPE:STRING=$buildType")) {
                            "CMAKE_BUILD_TYPE mismatch after configure: $directory"
                        }
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
