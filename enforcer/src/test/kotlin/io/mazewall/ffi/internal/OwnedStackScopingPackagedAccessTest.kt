package io.mazewall.ffi.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class OwnedStackScopingPackagedAccessTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "mazewall.managed.t4.fixture.classpath", matches = ".+")
    fun `managed t4 fixture proves checked original native ownership`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.t4.fixture.classpath")).split(java.io.File.pathSeparator)
        assertEquals(7, classpath.size)
        val definitions = assertUniqueEffectiveDefinitions(classpath)
        val output = runChild(classpath, "t4")
        println(output)
        assertTrue(output.contains("MANAGED_T4_OPENAT_PREPARED"), output)
        assertTrue(output.contains("MANAGED_T4_ACTUAL_LEASE_RETIRED"), output)
        assertTrue(output.contains("MANAGED_T4_PIPE_RECEIPT"), output)
        auditLoadedDefinitions(output, definitions)
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "mazewall.managed.t4.fixture.classpath", matches = ".+")
    fun `managed t4 fixture rejects invalid actual associations`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.t4.fixture.classpath")).split(java.io.File.pathSeparator)
        val original = java.nio.file.Path
            .of(requireNotNull(System.getProperty("mazewall.managed.t4.fixture.expected")))
            .toRealPath()
        val originalArgs = java.nio.file.Path
            .of(requireNotNull(System.getProperty("mazewall.managed.t4.fixture.vmArguments")))
            .toRealPath()
        val cases =
            listOf(
                "bad-build",
                "sys-build",
                "sys-digest",
                "sys-missing",
                "missing",
                "extra",
                "duplicate",
                "alias",
                "swapped",
                "bad-digest",
                "missing-zip",
                "binding",
                "dependency",
                "absent-binding",
                "foreign-loader",
            )
        for (scenario in cases) {
            val lines = java.nio.file.Files
                .readAllLines(original)
                .toMutableList()

            fun row(role: String): Int = lines.indexOfFirst { it.startsWith("$role\t") }.also { check(it >= 0) }
            when (scenario) {
                "bad-build", "sys-build" -> {
                    val index = row(if (scenario == "sys-build") "libsyslookup" else "libnio")
                    val columns = lines[index].split('\t').toMutableList()
                    check(columns.size == 5 && columns[3].isNotEmpty())
                    columns[3] = (if (columns[3][0] == '0') "1" else "0") + columns[3].substring(1)
                    lines[index] = columns.joinToString("\t")
                }
                "sys-digest" -> {
                    val index = row("libsyslookup")
                    val columns = lines[index].split('\t').toMutableList()
                    columns[2] = (if (columns[2][0] == '0') "1" else "0") + columns[2].substring(1)
                    lines[index] = columns.joinToString("\t")
                }
                "sys-missing" -> lines.removeAt(row("libsyslookup"))
                "missing" -> lines.removeAt(row("libgcc_s"))
                "missing-zip" -> lines.removeAt(row("libzip"))
                "extra" -> lines.add(lines[row("agent")].replaceFirst("agent\t", "unexpected\t"))
                "duplicate" -> lines.add(lines[row("agent")])
                "alias" -> lines[row("libpthread")] = lines[row("libdl")].replaceFirst("libdl\t", "libpthread\t")
                "swapped" -> {
                    val first = row("libnio")
                    val second = row("libjava")
                    val a = lines[first].split('\t').toMutableList()
                    val b = lines[second].split('\t').toMutableList()
                    for (field in 1..3) {
                        val temp = a[field]
                        a[field] = b[field]
                        b[field] = temp
                    }
                    lines[first] = a.joinToString("\t")
                    lines[second] = b.joinToString("\t")
                }
                "bad-digest" -> {
                    val index = row("helper")
                    val columns = lines[index].split('\t').toMutableList()
                    columns[2] = "0".repeat(64)
                    columns[3] = "sha256:" + columns[2]
                    lines[index] = columns.joinToString("\t")
                }
                "binding", "dependency", "absent-binding", "foreign-loader" -> Unit
                else -> error("unexpected negative fixture")
            }
            val directory = java.nio.file.Path
                .of("build", "managed-t4-fixture")
            java.nio.file.Files
                .createDirectories(directory)
            val manifest = java.nio.file.Files
                .createTempFile(directory, "reject-$scenario-", ".tsv")
                .toAbsolutePath()
            java.nio.file.Files
                .write(manifest, lines)
            val arguments = java.nio.file.Files
                .createTempFile(directory, "args-$scenario-", ".txt")
                .toAbsolutePath()
            val expectedArgument = "-Dmazewall.fixture.expected=$original"
            val argumentLines = java.nio.file.Files
                .readAllLines(originalArgs)
            check(argumentLines.count { it == expectedArgument } == 1)
            val changedArgs = argumentLines
                .map {
                if (it == expectedArgument) {
                    "-Dmazewall.fixture.expected=$manifest"
                } else if (it.startsWith("-Dmazewall.fixture.vmArguments=")) {
                    "-Dmazewall.fixture.vmArguments=$arguments"
                } else {
                    it
                }
            }.toMutableList()
            val foreign = scenario == "foreign-loader"
            if (foreign) changedArgs.add(0, "-Djava.system.class.loader=io.mazewall.ffi.internal.OwnedStackScopingPackagedFixture\$DelegatingLoader")
            java.nio.file.Files
                .write(arguments, changedArgs)
            val sha = java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    java.nio.file.Files
                    .readAllBytes(manifest),
                )
            val argsSha = java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    java.nio.file.Files
                    .readAllBytes(arguments),
                )
            println(
                "MANAGED_T4_NEGATIVE_INPUT scenario=$scenario manifest=$manifest sha256=${java.util.HexFormat.of().formatHex(
                    sha,
                )} arguments=$arguments sha256=${java.util.HexFormat.of().formatHex(argsSha)}",
            )
            val output = runChild(classpath, "t4-reject-$scenario", foreign, manifest.toString(), arguments.toString())
            println(output)
            if (foreign) {
                assertTrue(output.contains("mazewall live startup: wrong defining loader"), output)
                assertTrue(output.contains("MANAGED_T4_EARLY_LOADER_REAPED"), output)
            } else {
                assertTrue(output.contains("MANAGED_T4_REJECTED:t4-reject-$scenario"), output)
            }
            assertTrue(
                sha.contentEquals(
                    java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    java.nio.file.Files
                    .readAllBytes(manifest),
                ),
                ),
            )
            assertTrue(
                argsSha.contentEquals(
                    java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    java.nio.file.Files
                    .readAllBytes(arguments),
                ),
                ),
            )
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "mazewall.managed.t4.fixture.classpath", matches = ".+")
    fun `managed t4 fixture rejects duplicate nested helper class`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.t4.fixture.classpath")).split(java.io.File.pathSeparator).toMutableList()
        val member = "io/mazewall/ffi/internal/LiveStackBootstrap\$LiveDeploymentIdentity.class"
        val bytes = java.util.jar
            .JarFile(classpath[0])
            .use { jar -> jar.getInputStream(jar.getJarEntry(member)).readAllBytes() }
        val path = java.nio.file.Files
            .createTempFile(
                java.nio.file.Path
                .of("build"),
                    "t4-duplicate-",
                ".jar",
            )
        java.util.jar
            .JarOutputStream(
            java.nio.file.Files
            .newOutputStream(path),
        ).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry(member))
            jar.write(bytes)
            jar.closeEntry()
        }
        classpath[5] = path.toRealPath().toString()
        val failure = org.junit.jupiter.api
            .assertThrows<IllegalStateException> { assertUniqueEffectiveDefinitions(classpath) }
        assertTrue(failure.message.orEmpty().contains(member))
        println("MANAGED_T4_NESTED_DUPLICATE_REJECTED")
    }

    @Test
    fun `actual final jar child accepts the seven role component baseline`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        assertEquals(7, classpath.size)
        val definitions = assertUniqueEffectiveDefinitions(classpath)
        val output = runChild(classpath, "valid")
        assertTrue(output.contains("MANAGED_COMPONENT_VALID"))
        auditLoadedDefinitions(output, definitions)
        val report = java.nio.file.Path
            .of("build", "reports", "managed-layout", "class-loads.log")
        java.nio.file.Files
            .createDirectories(report.parent)
        java.nio.file.Files
            .writeString(report, output)
    }

    @Test
    fun `paired helper enforcer role and launch swaps reach and reject wrong code source`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator).toMutableList()
        java.util.Collections.swap(classpath, 0, 1)
        assertTrue(runChild(classpath, "reject").contains("MANAGED_REJECTED:live deployment: managed witness origin"))
    }

    @Test
    fun `same file in two managed roles is rejected before witness lookup`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator).toMutableList()
        classpath[1] = classpath[0]
        assertTrue(runChild(classpath, "reject").contains("MANAGED_REJECTED:live deployment: managed file alias"))
    }

    @Test
    fun `alternate system loader rejects mismatched defining loader`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        assertTrue(runChild(classpath, "reject", true).contains("MANAGED_REJECTED:live deployment: managed witness origin"))
    }

    @Test
    fun `missing extra duplicate and one-sided reorder reach structural rejection`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        for (mode in listOf("missing", "extra", "duplicate")) {
            assertTrue(runChild(classpath, mode).contains("MANAGED_REJECTED:live deployment: managed role set"), mode)
        }
        assertTrue(runChild(classpath, "one-sided").contains("MANAGED_REJECTED:live deployment: managed classpath order"))
    }

    @Test
    fun `extra actual classpath entry is rejected before file and witness checks`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        assertTrue(runChild(classpath + classpath.first(), "extra-actual").contains("MANAGED_REJECTED:live deployment: actual managed classpath"))
    }

    @Test
    fun `component rejects mismatched repo source`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        assertTrue(runChild(classpath, "repo-source").contains("MANAGED_REJECTED:live deployment: managed source association"))
    }

    @Test
    fun `component rejects empty external evidence`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        assertTrue(runChild(classpath, "external-source").contains("MANAGED_REJECTED:live deployment: managed external evidence"))
    }

    @Test
    fun `component digest mismatch and production missing authority refuse at their own checks`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        assertTrue(runChild(classpath, "bad-digest").contains("MANAGED_REJECTED:live deployment: managed test digest"))
        assertTrue(runChild(classpath, "production").contains("MANAGED_REJECTED:live deployment: managed expectations unavailable"))
    }

    @Test
    fun `duplicate effective test fixture class is rejected by test owned archive inventory`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator).toMutableList()
        val member = "io/mazewall/ffi/internal/OwnedStackScopingPackagedWorkload.class"
        val bytes = java.util.jar
            .JarFile(classpath[6])
            .use { jar -> jar.getInputStream(jar.getJarEntry(member)).readAllBytes() }
        val jar = java.nio.file.Files
            .createTempFile(
                java.nio.file.Path
                .of("build"),
                    "managed-duplicate-",
                ".jar",
            )
        java.util.jar
            .JarOutputStream(
            java.nio.file.Files
            .newOutputStream(jar),
        ).use { output ->
            output.putNextEntry(java.util.jar.JarEntry(member))
            output.write(bytes)
            output.closeEntry()
        }
        classpath[5] = jar.toRealPath().toString()
        val failure = org.junit.jupiter.api
            .assertThrows<IllegalStateException> { assertUniqueEffectiveDefinitions(classpath) }
        assertTrue(failure.message.orEmpty().contains(member))
    }

    @Test
    fun `packaged main producer links and refuses unavailable native bootstrap`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator)
        val output = runChild(classpath, "producer")
        println(output)
        assertTrue(output.contains("MANAGED_PRODUCER_UNSUPPORTED reason=MissingBinding"), output)
        assertTrue(output.contains("MANAGED_PRODUCER_DRAIN_COMPLETE"), output)
    }

    @Test
    fun `unreviewed service entry is rejected by test owned archive inventory`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator).toMutableList()
        val jar = java.nio.file.Files
            .createTempFile(
                java.nio.file.Path
                .of("build"),
                    "managed-service-",
                ".jar",
            )
        java.util.jar
            .JarOutputStream(
            java.nio.file.Files
            .newOutputStream(jar),
        ).use { output ->
            output.putNextEntry(java.util.jar.JarEntry("META-INF/services/example.Unreviewed"))
            output.write("unreviewed.Provider\n".toByteArray())
            output.closeEntry()
        }
        classpath[5] = jar.toRealPath().toString()
        val failure = org.junit.jupiter.api
            .assertThrows<IllegalStateException> { assertUniqueEffectiveDefinitions(classpath) }
        assertTrue(failure.message.orEmpty().contains("unexpected service"))
    }

    @Test
    fun `manifest classpath expansion is rejected before witness lookup`() {
        val classpath = requireNotNull(System.getProperty("mazewall.managed.fixture.classpath")).split(java.io.File.pathSeparator).toMutableList()
        val manifest = java.util.jar.Manifest().apply {
            mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[java.util.jar.Attributes.Name.CLASS_PATH] = "unreviewed.jar"
        }
        val jar = java.nio.file.Files
            .createTempFile(
                java.nio.file.Path
                .of("build"),
                    "managed-manifest-",
                ".jar",
            )
        java.util.jar
            .JarOutputStream(
                java.nio.file.Files
                .newOutputStream(jar),
                    manifest,
            ).use { }
        classpath[5] = jar.toRealPath().toString()
        assertTrue(runChild(classpath, "reject").contains("MANAGED_REJECTED:live deployment: managed manifest classpath"))
    }

    private fun assertUniqueEffectiveDefinitions(classpath: List<String>): Map<String, String> {
        val owners = mutableMapOf<String, String>()
        classpath.forEach { entry ->
            val path = java.nio.file.Path
                .of(entry)
                .toRealPath()
            val digest = java.util.HexFormat
                .of()
                .formatHex(
                    java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        java.nio.file.Files
                        .readAllBytes(path),
                    ),
                )
            println("MANAGED_ARCHIVE $path sha256=$digest")
            java.util.jar.JarFile(path.toFile(), false, java.util.zip.ZipFile.OPEN_READ, Runtime.version()).use { jar ->
                val attributes = jar.manifest?.mainAttributes
                for (name in listOf("Premain-Class", "Agent-Class", "Launcher-Agent-Class")) {
                    check(attributes?.getValue(name) == null) { "unexpected agent manifest in $path: $name" }
                }
                val services = jar
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                    .toList()
                check(services.isEmpty()) { "unexpected service in $path: ${services.map { it.name }}" }
                jar.versionedStream().filter { !it.isDirectory && it.name.endsWith(".class") && it.name != "module-info.class" }.forEach { member ->
                    val previous = owners.putIfAbsent(member.name, path.toString())
                    check(previous == null) { "Duplicate effective class ${member.name}: $previous and $path" }
                }
            }
        }
        return owners
    }

    private fun auditLoadedDefinitions(
        output: String,
        definitions: Map<String, String>,
    ) {
        val pattern = Regex("""\[class,load] ([^ ]+) source: (file:[^ ]+)""")
        var checked = 0
        output.lineSequence().forEach { line ->
            val match = pattern.find(line) ?: return@forEach
            val className = match.groupValues[1]
            val source = java.nio.file.Path
                .of(java.net.URI(match.groupValues[2]))
                .toRealPath()
                .toString()
            val entry = className.replace('.', '/') + ".class"
            assertEquals(source, definitions[entry], "loaded class does not match effective archive entry: $className")
            checked++
        }
        assertTrue(checked > 0, "no file-sourced classes observed")
        println("MANAGED_LOADED_CLASS_AUDIT count=$checked")
    }

    private fun runChild(
        classpath: List<String>,
        mode: String,
        alternateLoader: Boolean = false,
        expectedOverride: String? = null,
        argumentsOverride: String? = null,
    ): String {
        val command = mutableListOf(
            java.nio.file.Path
            .of(System.getProperty("java.home"), "bin", "java")
            .toString(),
        )
        if (alternateLoader) command.add("-Djava.system.class.loader=io.mazewall.ffi.internal.OwnedStackScopingPackagedFixture\$DelegatingLoader")
        if (mode == "valid" || mode.startsWith("t4")) command.add("-Xlog:class+load=info:stdout")
        if (mode.startsWith("t4")) {
            command.addAll(
                listOf(
                "-XX:+DisableAttachMechanism",
                    "-XX:-EnableDynamicAgentLoading",
                    "-Xshare:off",
                "-XX:+UnlockExperimentalVMOptions",
                    "-XX:-EnableJVMCI",
                    "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                "-Dmazewall.fixture.expected=" + (expectedOverride ?: requireNotNull(System.getProperty("mazewall.managed.t4.fixture.expected"))),
                "-Dmazewall.fixture.vmArguments=" + (argumentsOverride ?: requireNotNull(System.getProperty("mazewall.managed.t4.fixture.vmArguments"))),
                "-agentpath:" + requireNotNull(System.getProperty("mazewall.managed.t4.fixture.agent")) + "=live-stack=only",
            ),
            )
        }
        if (mode == "producer") command.add("--enable-native-access=ALL-UNNAMED")
        command.addAll(listOf("-cp", classpath.joinToString(java.io.File.pathSeparator), "io.mazewall.ffi.internal.OwnedStackScopingPackagedFixture"))
        if (mode != "valid") command.add(mode)
        val child = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val overflow = java.util.concurrent.atomic
            .AtomicBoolean()
        val fixtureReady = java.util.concurrent.CountDownLatch(1)
        val pump = Thread.ofPlatform().unstarted {
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line == "MANAGED_T4_CHILD_READY") fixtureReady.countDown()
                    if (output.length + line.length < 1_000_000) output.appendLine(line) else overflow.set(true)
                }
            }
        }
        val descendants = child.toHandle().descendants().toList()
        pump.start()
        var primary: Throwable? = null
        try {
            val pipes = if (mode.startsWith("t4")) {
                if (!alternateLoader) check(fixtureReady.await(15, TimeUnit.SECONDS)) { "fixture did not reach parent handoff" }
                val childFd = java.nio.file.Path
                    .of("/proc", child.pid().toString(), "fd")
                val pipeNames = listOf("0", "1").map {
                    java.nio.file.Files
                    .readSymbolicLink(childFd.resolve(it))
                    .toString()
                }
                check(pipeNames.distinct().size == 2 && pipeNames.all { it.startsWith("pipe:[") })
                java.nio.file.Files
                    .list(
                        java.nio.file.Path
                        .of("/proc/self/fd"),
                    ).use { paths ->
                    val candidates = paths.toList()
                    pipeNames.map { pipe ->
                        val matches = candidates.filter { path ->
                            try {
                                java.nio.file.Files
                                    .readSymbolicLink(path)
                                    .toString() == pipe
                            } catch (_: java.nio.file.NoSuchFileException) {
                                false
                            }
                        }
                        check(matches.size == 1) { "owned parent pipe descriptor unavailable or ambiguous: $pipe" }
                        matches.single() to pipe
                    }
                }.also {
                    if (!alternateLoader) child.outputStream.write(71)
                    child.outputStream.close()
                }
            } else {
                emptyList()
            }
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child timed out")
            pump.join(5000)
            assertFalse(pump.isAlive, "output pump still running")
            if (mode.startsWith("t4")) {
                for ((fd, original) in pipes) {
                    val current = try {
                        java.nio.file.Files
                            .readSymbolicLink(fd)
                            .toString()
                    } catch (_: java.nio.file.NoSuchFileException) {
                        null
                    }
                    check(current != original) { "original owned pipe still open: $fd" }
                }
                output.appendLine("MANAGED_T4_PIPE_RECEIPT count=${pipes.size}")
            }
            assertFalse(overflow.get(), "child output exceeded bound")
            if (alternateLoader && mode == "t4-reject-foreign-loader") {
                assertTrue(child.exitValue() != 0, output.toString())
                check(output.contains("mazewall live startup: wrong defining loader")) { output.toString() }
                output.appendLine("MANAGED_T4_EARLY_LOADER_REAPED")
            } else {
                assertEquals(0, child.exitValue(), output.toString())
            }
            assertTrue(descendants.none { it.isAlive }, "observed child descendant still alive")
            println("MANAGED_CHILD_REAPED pid=${child.pid()} exit=${child.exitValue()} observedDescendants=${descendants.size} pumpAlive=${pump.isAlive}")
            return output.toString()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                if (child.isAlive) {
                    child.destroyForcibly()
                    child.waitFor(5, TimeUnit.SECONDS)
                }
                pump.join(5000)
                assertFalse(child.isAlive, "child still alive after teardown")
                assertFalse(pump.isAlive, "output pump still running after teardown")
            } catch (teardown: Throwable) {
                val failure = primary
                if (failure == null) throw teardown
                try {
                    System.err.println("MANAGED_T4_TEARDOWN_FAILURE")
                } catch (_: Throwable) {
                    // Keep the original failure even if the receipt stream itself fails.
                }
                if (teardown !== failure) {
                    try {
                        failure.addSuppressed(teardown)
                    } catch (_: Throwable) {
                        // The retained primary must survive metadata failure.
                    }
                }
            }
            val failure = primary
            if (failure != null && !pump.isAlive) {
                try {
                    failure.addSuppressed(IllegalStateException("Captured child output after pump exit (overflow=${overflow.get()}):\n$output"))
                } catch (_: Throwable) {
                    // Diagnostic construction and attachment must not replace the original failure.
                }
            }
        }
    }
}
