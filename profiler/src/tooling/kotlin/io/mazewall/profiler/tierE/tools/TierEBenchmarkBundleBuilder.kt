package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.Comparator

/** Owns staged native/runtime construction and preserves verified prior bundles. */
public object TierEBenchmarkBundleBuilder {
    public fun build(
        repository: Path,
        bundle: Path,
        cacheRoot: Path,
        usdt: Boolean,
        force: Boolean,
        nativeBuildType: String,
        fingerprint: String = TierEBenchmarkBundleFingerprint.read(repository, usdt, nativeBuildType),
        fingerprintMillis: Long = 0,
        clockMillis: () -> Long = System::currentTimeMillis,
        execute: (List<String>, Duration, Path) -> Unit = { command, timeout, root ->
            val result = runContainerCommand(command, timeout, outputFile = root.resolve("bundle-build.log"))
            check(result.exitCode == 0) { "Tier E bundle build failed: ${command.first()} (${result.exitCode}): ${result.output}" }
        },
    ): String {
        require(nativeBuildType.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid native build type" }
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundle fingerprint" }
        require(fingerprintMillis >= 0) { "Invalid fingerprint duration" }
        val started = clockMillis()
        Files.createDirectories(cacheRoot)
        FileChannel.open(cacheRoot.resolve("bundle-build.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                val prior = if (Files.exists(bundle, NOFOLLOW_LINKS)) {
                    TierEBenchmarkBundleManifest.verify(bundle)
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(Files.readString(bundle.resolve("manifest.json")))
                        .jsonObject
                        .getValue("input_fingerprint")
                        .jsonPrimitive.content
                } else {
                    null
                }
                if (!force && prior == fingerprint) return "reused"
                val native = cacheRoot.resolve("native-$fingerprint")
                Files.createDirectories(native)
                val configureStarted = clockMillis()
                execute(
                    listOf(
                        "cmake",
                        "-S",
                        repository.resolve("profiler-native").toString(),
                        "-B",
                        native.toString(),
                    "-DCMAKE_BUILD_TYPE=$nativeBuildType",
                        "-DMAZEWALL_ENABLE_TIER_E_USDT=$usdt",
                    ),
                        Duration.ofMinutes(5),
                    repository,
                )
                val configureMillis = clockMillis() - configureStarted
                val nativeStarted = clockMillis()
                execute(listOf("cmake", "--build", native.toString(), "-j2"), Duration.ofMinutes(5), repository)
                val nativeMillis = clockMillis() - nativeStarted
                val runtimeCache = cacheRoot.resolve("runtime-$fingerprint")
                val runtime = runtimeCache.resolve("runtime")
                val gradleStarted = clockMillis()
                if (Files.exists(runtimeCache, NOFOLLOW_LINKS) && !Files.isRegularFile(runtimeCache.resolve("input-fingerprint"), NOFOLLOW_LINKS)) {
                    error("Tier E runtime cache is incomplete")
                }
                if (!Files.exists(runtimeCache, NOFOLLOW_LINKS)) {
                    val stage = Files.createTempDirectory(cacheRoot, ".tier-e-runtime-")
                    try {
                        val gradleHome = Files.createDirectories(cacheRoot.resolve("gradle-home"))
                        val gradleProjectCache = Files.createDirectories(cacheRoot.resolve("gradle-project-cache"))
                        execute(
                            listOf(
                                "env",
                                "GRADLE_USER_HOME=$gradleHome",
                                repository.resolve("gradlew").toString(),
                            "--no-daemon",
                                "--project-cache-dir",
                                gradleProjectCache.toString(),
                                ":profiler:installDist",
                            ),
                                Duration.ofMinutes(10),
                            repository,
                        )
                        copyTree(repository.resolve("profiler/build/install/tier-e-daemon/lib"), stage.resolve("runtime"))
                        Files.writeString(stage.resolve("input-fingerprint"), "$fingerprint\n")
                        Files.move(stage, runtimeCache, ATOMIC_MOVE)
                    } finally {
                        if (Files.exists(stage, NOFOLLOW_LINKS)) deleteTree(stage)
                    }
                }
                require(Files.readString(runtimeCache.resolve("input-fingerprint")).trim() == fingerprint && Files.isDirectory(runtime, NOFOLLOW_LINKS)) {
                    "Tier E runtime cache fingerprint mismatch"
                }
                val gradleMillis = clockMillis() - gradleStarted
                val stagingStarted = clockMillis()
                val parent = requireNotNull(bundle.toAbsolutePath().parent)
                Files.createDirectories(parent)
                val staged = Files.createTempDirectory(parent, ".tier-e-bundle-")
                try {
                    Files.copy(native.resolve("libmazewall_stack_agent.so"), staged.resolve("libmazewall_stack_agent.so"))
                    copyTree(runtime, staged.resolve("runtime"))
                    TierEBenchmarkBundleManifest.write(
                        staged,
                        fingerprint,
                        mapOf(
                        "fingerprint" to fingerprintMillis,
                        "native_configure" to configureMillis,
                        "native_build" to nativeMillis,
                        "gradle_install" to gradleMillis,
                        "staging" to (clockMillis() - stagingStarted),
                        "total" to (clockMillis() - started + fingerprintMillis),
                    ),
                    )
                    TierEBenchmarkBundleManifest.verify(staged, fingerprint)
                    if (prior != null) {
                        val replacement = Path.of("$bundle.replaced-${prior.ifEmpty { "unbound" }}")
                        require(!Files.exists(replacement, NOFOLLOW_LINKS)) { "Refusing to overwrite prior bundle" }
                        Files.move(bundle, replacement, ATOMIC_MOVE)
                    }
                    Files.move(staged, bundle, ATOMIC_MOVE)
                } finally {
                    if (Files.exists(staged, NOFOLLOW_LINKS)) deleteTree(staged)
                }
                return "built"
            }
        }
    }

    private fun copyTree(
        source: Path,
        destination: Path,
    ) {
        require(Files.isDirectory(source, NOFOLLOW_LINKS)) { "Missing runtime distribution" }
        Files.walk(source).use { stream ->
            for (file in stream.toList()) {
                val target = destination.resolve(source.relativize(file).toString())
                when {
                    Files.isDirectory(file, NOFOLLOW_LINKS) -> Files.createDirectories(target)
                    Files.isRegularFile(file, NOFOLLOW_LINKS) -> Files.copy(file, target)
                    else -> error("Runtime distribution contains a symlink or special file")
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
