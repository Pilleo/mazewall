package io.mazewall.ffi.networking

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.supervisor.*
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.native
import io.mazewall.ffi.memory.readString
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.NativeNetworking
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.lang.foreign.ValueLayout
import java.lang.ref.WeakReference
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Irreversible restrictions run only in an owned disposable helper under the isolated runner. */
class ConfinedHelperProcessIntegrationTest {
    @Test
    fun `child observation validates isolated timeout before launch`() {
        check(observationTimeout(null) == 120L)
        check(observationTimeout("1") == 1L)
        check(observationTimeout("120") == 120L)
        for (invalid in listOf("0", "121", "-1", "not-a-number", "999999999999999999999999")) {
            check(runCatching { observationTimeout(invalid) }.isFailure)
        }
    }

    private fun observationTimeout(raw: String?): Long {
        val seconds = raw?.toLongOrNull() ?: if (raw == null) 120L else throw IllegalArgumentException("Invalid isolated timeout")
        require(seconds in 1..120) { "Invalid isolated timeout" }
        return seconds
    }

    private data class ObservedArgv(
        val classpath: String,
        val main: String,
        val agentpath: Boolean,
        val javaagent: Boolean,
    )

    @Test
    fun `child observation rejects JVM option environment presence`() {
        for (key in listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            check(hasInjectedJvmOptions(mapOf(key to "")))
            check(hasInjectedJvmOptions(mapOf(key to "value")))
        }
        check(!hasInjectedJvmOptions(mapOf("UNRELATED" to "value")))
        val keysOnly = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> get() = error("values must not be enumerated")

            override fun containsKey(key: String): Boolean = key == "JAVA_TOOL_OPTIONS"
        }
        check(hasInjectedJvmOptions(keysOnly))
    }

    @Test
    fun `child observation parses selected bounded argv only`() {
        val argv = listOf("java", "-cp", "/a:/b", SocketHelperMain::class.java.name).joinToString("\u0000", postfix = "\u0000").toByteArray()
        check(parseObservedArgv(argv).classpath == "/a:/b")
        check(runCatching { parseObservedArgv(argv.dropLast(1).toByteArray()) }.isFailure)
        val agentArgv = listOf("java", "-agentpath:private", "-javaagent:private", "-cp", "/a:/b", SocketHelperMain::class.java.name).joinToString("\u0000", postfix = "\u0000").toByteArray()
        check(parseObservedArgv(agentArgv).let { it.agentpath && it.javaagent && it.main == SocketHelperMain::class.java.name })
        val duplicate = listOf("java", "-cp", "/a", "-cp", "/b", SocketHelperMain::class.java.name).joinToString("\u0000", postfix = "\u0000").toByteArray()
        check(runCatching { parseObservedArgv(duplicate) }.isFailure)
        val maxArgs = listOf("java") + List(252) { "-x" } + listOf("-cp", "/a", SocketHelperMain::class.java.name)
        check(parseObservedArgv(maxArgs.joinToString("\u0000", postfix = "\u0000").toByteArray()).classpath == "/a")
        check(runCatching { parseObservedArgv((maxArgs + "extra").joinToString("\u0000", postfix = "\u0000").toByteArray()) }.isFailure)
        check(runCatching { parseObservedArgv(byteArrayOf(0xff.toByte(), 0)) }.isFailure)
        check(runCatching { parseObservedArgv(ByteArray(64 * 1024 + 1)) }.isFailure)
    }

    @Test
    fun `child observation validates bounded mapped paths`() {
        val line = "1000-2000 r--p 00000000 00:01 12 /path with space\n"
        check(parseObservedMaps(line.toByteArray()) == listOf("/path with space"))
        check(parseObservedMaps("1000-2000 rw-p 00000000 00:00 0     \n".toByteArray()).isEmpty())
        check(parseObservedMaps("1000-2000 r-xp 00000000 00:00 0 [vdso]\n".toByteArray()).isEmpty())
        check(runCatching { parseObservedMaps("1000-2000 r--p 00000000 00:01 invalid     \n".toByteArray()) }.isFailure)
        check(runCatching { parseObservedMaps("1000-2000 r--p 00000000 00:01 12     \n".toByteArray()) }.isFailure)
        check(runCatching { parseObservedMaps((line.trimEnd() + " (deleted)\n").toByteArray()) }.isFailure)
        check(runCatching { parseObservedMaps("broken\n".toByteArray()) }.isFailure)
        check(runCatching { parseObservedMaps(line.dropLast(1).toByteArray()) }.isFailure)
        check(parseObservedMaps(line.repeat(8192).toByteArray()).size == 8192)
        check(runCatching { parseObservedMaps(line.repeat(8193).toByteArray()) }.isFailure)
        check(runCatching { parseObservedMaps(ByteArray(1024 * 1024 + 1)) }.isFailure)
    }

    @Test
    fun `child observation enforces elapsed acceptance ceiling`() {
        check(!withinObservationTime(100L, 10_000_000_101L))
        check(withinObservationTime(100L, 10_000_000_100L))
        check(!withinObservationTime(100L, 99L))
        check(withinObservationTime(Long.MAX_VALUE - 5, Long.MIN_VALUE + 4))
    }

    @Test
    fun `child observation parses process incarnation`() {
        val fields = listOf("S") + List(18) { "0" } + "123"
        val stat = ("42 (a ) b) " + fields.joinToString(" ") + "\n").toByteArray()
        check(parseObservedStartTicks(42, stat) == 123L)
        check(runCatching { parseObservedStartTicks(43, stat) }.isFailure)
        check(runCatching { parseObservedStartTicks(42, stat.dropLast(1).toByteArray()) }.isFailure)
        check(runCatching { parseObservedStartTicks(42, ByteArray(8 * 1024 + 1)) }.isFailure)
        val overflow = ("42 (a) " + (listOf("S") + List(18) { "0" } + "999999999999999999999999").joinToString(" ") + "\n").toByteArray()
        check(runCatching { parseObservedStartTicks(42, overflow) }.isFailure)
    }

    private fun hasInjectedJvmOptions(environment: Map<String, String>): Boolean = listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").any(environment::containsKey)

    private fun parseObservedArgv(bytes: ByteArray): ObservedArgv {
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 && bytes.last() == 0.toByte()) { "Invalid child argv" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val arguments = text.dropLast(1).split('\u0000')
        require(arguments.size in 1..256 && arguments.none(String::isEmpty)) { "Invalid child argv" }
        val main = SocketHelperMain::class.java.name
        val mainIndices = arguments.indices.filter { arguments[it] == main }
        val classpathIndices = arguments.indices.filter { arguments[it] == "-cp" }
        require(mainIndices.size == 1 && classpathIndices.size == 1) { "Invalid child launch layout" }
        val mainIndex = mainIndices.single()
        val cpIndex = classpathIndices.single()
        require(cpIndex > 0 && cpIndex + 2 == mainIndex) { "Invalid child launch layout" }
        val flags = arguments.subList(1, cpIndex)
        return ObservedArgv(
            arguments[cpIndex + 1],
            main,
            flags.any { it.startsWith("-agentpath:") },
            flags.any { it.startsWith("-javaagent:") },
        )
    }

    private fun parseObservedMaps(bytes: ByteArray): List<String> {
        require(bytes.isNotEmpty() && bytes.size <= 1024 * 1024) { "Invalid child maps" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(text.endsWith('\n') && '\u0000' !in text) { "Invalid child maps" }
        val lines = text.dropLast(1).split('\n')
        require(lines.size <= 8192) { "Invalid child maps" }
        return lines.mapNotNull { line ->
            val fields = line.split(Regex(" +"), limit = 6)
            require(fields.size in 5..6) { "Invalid child maps row" }
            val addresses = fields[0].split('-')
            require(addresses.size == 2) { "Invalid child maps row" }
            val start = addresses[0].toULongOrNull(16)
            val end = addresses[1].toULongOrNull(16)
            require(start != null && end != null && start < end) { "Invalid child maps row" }
            require(fields[1].matches(Regex("[r-][w-][x-][ps]"))) { "Invalid child maps row" }
            require(fields[2].toULongOrNull(16) != null && fields[3].matches(Regex("[0-9a-fA-F]+:[0-9a-fA-F]+"))) { "Invalid child maps row" }
            val inode = fields[4].toULongOrNull()
            require(inode != null) { "Invalid child maps row" }
            val path = fields.getOrNull(5)?.takeUnless { it.isEmpty() || it.all { char -> char == ' ' } }
            require(path != null || inode == 0uL) { "Unidentified child mapping" }
            require(path == null || path.startsWith('/') || path.startsWith('[') && path.endsWith(']')) { "Invalid child mapping" }
            require(path == null || !path.endsWith(" (deleted)")) { "Deleted child mapping" }
            require(path == null || '\\' !in path) { "Ambiguous child mapping" }
            path?.takeIf { it.startsWith('/') }
        }
    }

    private fun withinObservationTime(
        start: Long,
        now: Long,
    ): Boolean {
        val elapsed = now - start
        return elapsed >= 0 && elapsed <= 10_000_000_000L
    }

    private fun parseObservedStartTicks(
        pid: Int,
        bytes: ByteArray,
    ): Long {
        require(pid > 0 && bytes.isNotEmpty() && bytes.size <= 8 * 1024) { "Invalid child stat" }
        val stat = bytes.decodeToString(throwOnInvalidSequence = true)
        require(stat.endsWith('\n') && '\u0000' !in stat) { "Invalid child stat" }
        val identity = ProcProcessIdentityResolver { stat }.resolve(Pid(pid))
        return requireNotNull(identity?.startTimeTicks?.takeIf { it > 0 }) { "Invalid child identity" }
    }

    @Test
    fun `actual owned helper child startup observation`() {
        observationTimeout(System.getProperty("io.mazewall.isolatedTestTimeoutSeconds"))
        check(!hasInjectedJvmOptions(System.getenv())) { "Injected JVM option environment is present" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyObservedAllowed")
    }

    private fun readOwnedProc(
        pid: Int,
        name: String,
        limit: Int,
    ): ByteArray {
        require(name in setOf("stat", "cmdline", "maps"))
        val bytes = Files.newInputStream(Path.of("/proc", pid.toString(), name)).use { it.readNBytes(limit + 1) }
        require(bytes.isNotEmpty() && bytes.size <= limit) { "Invalid owned child observation" }
        return bytes
    }

    private fun requireObservationTime(started: Long) {
        check(withinObservationTime(started, System.nanoTime())) { "Owned child observation exceeded elapsed ceiling" }
    }

    private fun observeOwnedChild(
        helper: SocketHelperProcess,
        classpath: List<Path>,
        classpathDigests: List<String>,
        parentMapped: List<Path>,
        started: Long,
    ) {
        val handle = helper.processHandle
        val identity = requireNotNull(helper.confinedIdentity) { "Owned child identity unavailable" }
        val pid = identity.pid.value
        check(pid > 0 && handle.pid() == pid.toLong()) { "Owned child identity mismatch" }

        fun requireIdentity() {
            requireObservationTime(started)
            check(handle.isAlive && helper.confinedIdentity == identity) { "Owned child exited or changed" }
            val stat = readOwnedProc(pid, "stat", 8 * 1024)
            check(parseObservedStartTicks(pid, stat) == identity.startTimeTicks && handle.isAlive) { "Owned child incarnation changed" }
            requireObservationTime(started)
        }
        requireIdentity()
        val commandBytes = readOwnedProc(pid, "cmdline", 64 * 1024)
        requireIdentity()
        val argv = parseObservedArgv(commandBytes)
        check(classpath.none { it.toString().contains(File.pathSeparator) || '\n' in it.toString() }) { "Invalid fixture classpath" }
        check(argv.classpath == classpath.joinToString(File.pathSeparator)) { "Owned child classpath differs" }
        requireIdentity()
        val mapsBytes = readOwnedProc(pid, "maps", 1024 * 1024)
        requireIdentity()
        val childMappings = parseObservedMaps(mapsBytes).distinct().sorted()
        val parentPaths = parentMapped.map(Path::toString).toSet()
        requireObservationTime(started)
        println("OWNED_CHILD pid=$pid start-ticks=${identity.startTimeTicks} main=${argv.main} agentpath=${argv.agentpath} javaagent=${argv.javaagent}")
        check(classpath.size == classpathDigests.size) { "Invalid fixture expectations" }
        classpath.forEachIndexed { index, path -> println("FIXTURE_CLASSPATH index=$index path=$path sha256=${classpathDigests[index]} no-writers=test-lifetime") }
        childMappings.forEach { path -> println("CHILD_MAPPING path=$path parent-sample=${path in parentPaths}") }
        println("OWNED_CHILD_MAPS count=${childMappings.size} child-only=${childMappings.count { it !in parentPaths }}")
        requireObservationTime(started)
    }

    @Test
    fun `mapped support includes read only shared regular files`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifySharedMappedSupport")
    }

    private fun verifySharedMappedSupport() {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build/confined-helper-fixtures")), "mapped-support-")
        val file = Files.writeString(directory.resolve("mapped-file"), "mapped-support").toRealPath()
        val writable = Files.writeString(directory.resolve("writable-file"), "writable-support").toRealPath()
        FileChannel.open(file).use { channel ->
            val mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            FileChannel.open(writable, StandardOpenOption.READ, StandardOpenOption.WRITE).use { writableChannel ->
                val writableMapping = writableChannel.map(FileChannel.MapMode.READ_WRITE, 0, writableChannel.size())
                val rows = Files.readAllLines(Path.of("/proc/self/maps"))
                val matches = rows.filter { it.split(Regex(" +"), limit = 6).getOrNull(5) == file.toString() }
                val inode = (Files.getAttribute(file, "unix:ino") as Number).toLong()
                check(
                    matches.any { row ->
                        val fields = row.split(Regex(" +"), limit = 6)
                        fields[1] == "r--s" && fields[4].toLong() == inode
                    },
                ) { "Expected a read-only shared mapping of inode $inode: $matches" }
                val writableRows = rows.filter { it.split(Regex(" +"), limit = 6).getOrNull(5) == writable.toString() }
                val writableInode = (Files.getAttribute(writable, "unix:ino") as Number).toLong()
                check(
                    writableRows.any { row ->
                        val fields = row.split(Regex(" +"), limit = 6)
                        fields[1] == "rw-s" && fields[4].toLong() == writableInode
                    },
                ) { "Expected a writable shared mapping of inode $writableInode: $writableRows" }
                val selected = mappedSupport(rows).map { it.toRealPath() }
                check(writable !in selected) { "Writable shared mapping selected for fixture support: $writableRows" }
                check(file in selected) { "Read-only shared mapping omitted from fixture support: $matches" }
                java.lang.ref.Reference
                    .reachabilityFence(mapping)
                java.lang.ref.Reference
                    .reachabilityFence(writableMapping)
            }
        }
    }

    private fun mappedSupport(rows: List<String>): List<Path> =
        rows.mapNotNull { row ->
            val fields = row.split(Regex(" +"), limit = 6)
            fields
                .getOrNull(5)
                ?.takeIf { it.startsWith('/') && !it.endsWith(" (deleted)") && (fields[1].endsWith('p') || fields[1][1] != 'w' && fields[1].endsWith('s')) }
                ?.let(Path::of)
                ?.takeIf(Files::isRegularFile)
        }

    internal enum class Case {
        Allowed,
        RuntimeSupport,
        WrongRoot,
        WrongGeneration,
        WrongPurpose,
        LegacyOpen3,
        LegacyAuthority4,
        PartialHeader,
        PartialPayload,
        MissingComplete,
        MissingExpected,
        MissingReceived,
        ForbiddenPreopen,
        ForbiddenPinnedReopen,
        ChildGc,
        KernelBackpressure,
        ResultCloseFailure,
        OwnerDeath,
    }

    @ParameterizedTest
    @EnumSource(value = Case::class, mode = EnumSource.Mode.EXCLUDE, names = ["ForbiddenPreopen", "ForbiddenPinnedReopen", "ChildGc", "KernelBackpressure", "ResultCloseFailure", "OwnerDeath"])
    internal fun `real prestarted helper qualifies each control and retires the same child`(case: Case) {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCase", case.name)
    }

    @ParameterizedTest
    @EnumSource(value = Case::class, names = ["ForbiddenPreopen", "ForbiddenPinnedReopen", "ChildGc", "KernelBackpressure", "ResultCloseFailure", "OwnerDeath"])
    internal fun nativeChildControls(case: Case) {
        if (case == Case.OwnerDeath) {
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyOwnerDeath")
        } else {
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCase", case.name)
        }
    }

    private fun verifyOwnerDeath() {
        val owner = ProcessBuilder(JvmChildProcess.commandLine(JvmChildSpec(OwnerFixture::class.java.name, javaAgents = JavaAgentSelection.None)))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        var helper: ProcessHandle? = null
        val cleanup = SocketCleanup()
        cleanup.attempt {
            val marker = java.util.concurrent.CompletableFuture
                .supplyAsync {
                owner.inputReader().use { input ->
                    generateSequence(input::readLine).onEach(::println).first { it.startsWith("OWNER_DEATH_READY ") }
                }
            }.get(15, TimeUnit.SECONDS)
                .split(' ')
            helper = owner.descendants().use { descendants -> descendants.filter { it.pid() == marker[1].toLong() }.findFirst().orElseThrow() }
            val acquired = requireNotNull(helper)
            check(
                acquired
                .info()
                .startInstant()
                .orElseThrow()
                .toEpochMilli() == marker[2].toLong(),
            )
            check(acquired.isAlive)
            val exited = acquired.onExit()
            owner.destroyForcibly()
            check(owner.waitFor(5, TimeUnit.SECONDS))
            exited.get(8, TimeUnit.SECONDS)
            check(!acquired.isAlive)
            println("OWNER_DEATH child=${acquired.pid()} physically-exited=true")
        }
        cleanup.attempt {
            owner.destroyForcibly()
            check(owner.waitFor(5, TimeUnit.SECONDS))
        }
        cleanup.attempt {
            helper?.let { acquired ->
                if (acquired.isAlive) acquired.destroyForcibly()
                acquired.onExit().get(5, TimeUnit.SECONDS)
            }
        }
        cleanup.rethrow()
    }

    internal object OwnerFixture {
        @JvmStatic
        fun main(args: Array<String>) = ConfinedHelperProcessIntegrationTest().verifyCase(Case.OwnerDeath.name)
    }

    private fun verifyCase(case: String) = runCase(case, observeChild = false)

    private fun verifyObservedAllowed() = runCase(Case.Allowed.name, observeChild = true)

    private fun runCase(
        case: String,
        observeChild: Boolean,
    ) {
        val selected = Case.valueOf(case)
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build/confined-helper-fixtures")), "$case-").toAbsolutePath()
        val application = Files.createDirectory(directory.resolve("application"))
        Files.writeString(application.resolve("file"), "confined-object")
        val outside = Files.writeString(directory.resolve("forbidden"), "forbidden-object")
        check(Files.readString(outside) == "forbidden-object")
        Files.createSymbolicLink(application.resolve("escape"), outside)
        val childEffects = selected in setOf(Case.ForbiddenPreopen, Case.ForbiddenPinnedReopen, Case.ChildGc, Case.KernelBackpressure, Case.ResultCloseFailure)
        val childLog = directory.resolve("child-effects.log")
        val classpath = runtimeClasspath(directory, childEffects)
        val classpathDigests = if (observeChild) classpath.map(::digest) else emptyList()
        val fault = WireFault(selected, LinuxNative.snapshot())
        LinuxNative.setEngine(fault)
        val native = LinuxNative.snapshot()
        val ancestry = HelperSecurityObservation().observeAncestry(Pid(ProcessHandle.current().pid().toInt()), native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
        val mappingRows = Files.readAllLines(Path.of("/proc/self/maps"))
        val mapped = mappedSupport(mappingRows)
        val support = (classpath + mapped + listOf(Path.of(System.getProperty("java.home"), "lib/modules"))).map { it.toRealPath() }.distinct()
        check(support.size in 1..64)
        val reviewed = support.map { ReviewedHelperSupport(it, digest(it)) }
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(application)
        support.forEach { builder.allowFsRead(it) }
        val policy = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, builder.build())
        println("CASE=$case kernel=${System.getProperty("os.version")} jdk=${System.getProperty("java.runtime.version")} arch=${System.getProperty("os.arch")} fixture=$directory")
        println("ANCESTRY=${ancestry.ancestry.canonical}")
        reviewed.forEach { println("SUPPORT path=${it.path} sha256=${it.sha256} no-writers=test-lifetime") }
        var child: ProcessHandle? = null
        var observedHelper: SocketHelperProcess? = null
        var observationStarted = 0L
        var creates = 0
        val wrongRoot = if (selected == Case.WrongRoot) PreparedPath.open(outside, native).also { fault.wrongRoot = it.descriptor.value } else null
        val resources = PreparedResourceSet.capture(policy.definition, native)
        try {
            val binding = HelperBinding(
                ancestry.identity.pid,
                ancestry.identity.startTimeTicks,
                1,
                resources.generation,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(policy.toJson().toByteArray())),
            )
            val plan = PreparedHelperReadPlan.capture(policy, resources, binding, reviewed, HelperSupportLifetime.NoWriters, native, ancestry.ancestry)
            try {
                SocketHelperPool(PoolProvisioning.PrestartedConfined) {
                    creates++
                    if (observeChild) observationStarted = System.nanoTime()
                    SocketHelperProcess
                        .startConfined(classpath.joinToString(File.pathSeparator)) { builder ->
                        if (observeChild) check(!hasInjectedJvmOptions(builder.environment())) { "Injected JVM option environment is present" }
                        if (childEffects) {
                            val command = builder.command()
                            val main = command.indexOf(SocketHelperMain::class.java.name)
                            check(main > 0)
                            command[main] = ChildEffects::class.java.name
                            command.addAll(main + 1, listOf(selected.name, outside.toString()))
                            builder.redirectError(childLog.toFile())
                        }
                        builder.start()
                    }.also {
                        child = it.processHandle
                        if (observeChild) observedHelper = it
                    }
                }.use { pool ->
                    pool.prewarm()
                    check(creates == 1 && requireNotNull(child).isAlive)
                    if (observeChild) observeOwnedChild(requireNotNull(observedHelper), classpath, classpathDigests, mapped, observationStarted)
                    println("CHILD pid=${child.pid()} start=${child.info().startInstant()}")
                    val setupStarted = System.nanoTime()
                    val cap = ConfinedDeadline.mint(native) { 5_000_000_000L }
                    val session = pool.reserveConfined(plan, cap)
                    session.use {
                        val ready = session.setupConfined(cap).get(6, TimeUnit.SECONDS)
                        if (fault.setupFault) {
                            check(ready is ConfinedSetupResult.Rejected) { "Fault produced readiness: $ready" }
                            check(fault.injected && fault.resultFd == null)
                            when (selected) {
                                Case.WrongRoot -> check(ready.reason == ConfinedRejectReason.Identity)
                                Case.ForbiddenPreopen -> check(ready.reason == ConfinedRejectReason.RuntimeClosure)
                                Case.PartialHeader, Case.PartialPayload, Case.MissingComplete, Case.KernelBackpressure -> {
                                    check(ready.reason == ConfinedRejectReason.Timeout) { "Missing bounded setup timeout: $ready" }
                                    bounded(setupStarted)
                                }
                                Case.Allowed, Case.RuntimeSupport, Case.WrongGeneration, Case.WrongPurpose,
                                Case.LegacyOpen3, Case.LegacyAuthority4, Case.MissingExpected, Case.MissingReceived,
                                Case.ForbiddenPinnedReopen, Case.ChildGc, Case.ResultCloseFailure, Case.OwnerDeath,
                                -> error("Unexpected setup fault")
                            }
                            println("REJECTED case=$case reason=${ready.reason} errno=${ready.errno} readable-exports=0")
                        } else {
                            check(ready == ConfinedSetupResult.Ready(plan.manifest.binding)) { "Expected installed helper readiness, got $ready" }
                            println("READY case=$case binding=${plan.manifest.binding.manifestDigest}")
                        val request = ConfinedReadRequest(plan.manifest.binding, 1, HelperPurpose.Application, 0, "file")
                        val result = session.readConfined(request, ConfinedDeadline.mint(native) { 5_000_000_000L }).get(6, TimeUnit.SECONDS)
                        check(result is SupervisorDescriptorResult.Opened) { "Allowed helper read refused: $result" }
                        result.descriptor.use { owner ->
                            val bytes = NativeArena.ofConfined().use { arena ->
                                val buffer = arena.allocate(64)
                                val count = native.memory.read(owner.handle, buffer, 64).getOrThrow("allowed direct FD read")
                                check(count in 1..64)
                                ByteArray(count.toInt()).also { bytes ->
                                    ManagedSegment.copy(buffer, 0, bytes, 0, bytes.size)
                                    check(native.memory.read(owner.handle, buffer, 64).getOrThrow("allowed EOF") == 0L)
                                }
                            }
                            check(bytes.toString(Charsets.UTF_8) == "confined-object")
                            val actual = Files.readAttributes(Path.of("/proc/self/fd/${owner.handle.value}"), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                            check(actual == Files.readAttributes(application.resolve("file"), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
                            println("ALLOWED identity=$actual bytes=${bytes.size} EOF=true")
                        }
                            when (selected) {
                                Case.OwnerDeath -> {
                                    val acquired = requireNotNull(child)
                                    println("OWNER_DEATH_READY ${acquired.pid()} ${acquired.info().startInstant().orElseThrow().toEpochMilli()}")
                                    System.out.flush()
                                    check(System.`in`.read() == 0x52) { "Owner fixture input ended before controlled death" }
                                    error("Owner fixture must be killed while the sealed helper is idle")
                                }
                                Case.Allowed, Case.ChildGc -> {
                                    val denied = session.readConfined(request.copy(id = 2, suffix = "escape"), ConfinedDeadline.mint(native) { 5_000_000_000L }).get(6, TimeUnit.SECONDS)
                                    check(denied is SupervisorDescriptorResult.Denied && denied.errno == 40) { "Symlink escape did not preserve ELOOP: $denied" }
                                }
                                Case.ForbiddenPinnedReopen -> {
                                    val denied = session.readConfined(request.copy(id = 2, suffix = "forbidden-probe"), ConfinedDeadline.mint(native) { 5_000_000_000L }).get(6, TimeUnit.SECONDS)
                                    check(denied is SupervisorDescriptorResult.Denied && denied.errno in setOf(1, 13)) { "Forbidden pinned object was not denied by the kernel: $denied" }
                                    println("FORBIDDEN pinned-reopen-errno=${denied.errno} readable-exports=0")
                                }
                                Case.RuntimeSupport -> {
                                    val result = session
                                        .readConfined(
                                        ConfinedReadRequest(plan.manifest.binding, 2, HelperPurpose.RuntimeSupport, 1, "."),
                                        ConfinedDeadline.mint(native) { 5_000_000_000L },
                                    ).get(6, TimeUnit.SECONDS)
                                    check(result is SupervisorDescriptorResult.Opened)
                                    result.descriptor.use { owner ->
                                        val digest = MessageDigest.getInstance("SHA-256")
                                        NativeArena.ofConfined().use { arena ->
                                            val buffer = arena.allocate(65536)
                                            while (true) {
                                                val count = native.memory.read(owner.handle, buffer, 65536).getOrThrow("runtime support read")
                                                check(count in 0..65536)
                                                if (count == 0L) break
                                                val bytes = ByteArray(count.toInt())
                                                ManagedSegment.copy(buffer, 0, bytes, 0, bytes.size)
                                                digest.update(bytes)
                                            }
                                        }
                                        check(HexFormat.of().formatHex(digest.digest()) == reviewed.first().sha256)
                                        println("RUNTIME exact-digest=true EOF=true index=1")
                                    }
                                }
                                Case.WrongGeneration, Case.WrongPurpose, Case.LegacyOpen3, Case.LegacyAuthority4, Case.MissingExpected, Case.MissingReceived, Case.ResultCloseFailure -> {
                                    fault.armed = true
                                    val started = System.nanoTime()
                                    val failure = runCatching {
                                        session
                                        .readConfined(
                                        request.copy(id = 2),
                                        ConfinedDeadline.mint(native) { 5_000_000_000L },
                                    ).get(6, TimeUnit.SECONDS)
                                    }.exceptionOrNull()
                                    check(failure is ExecutionException) { "Read must reject internally without exposing a descriptor: $failure" }
                                    check(fault.injected || selected == Case.ResultCloseFailure)
                                    if (selected == Case.ResultCloseFailure) {
                                        val rejected = failure.cause
                                        check(
                                            rejected is ConfinedHelperRejection && rejected.reason == ConfinedRejectReason.RuntimeClosure && rejected.errno == 5,
                                        ) { "Native close errno was lost: $rejected" }
                                    }
                                    bounded(started)
                                    if (selected == Case.MissingReceived || selected == Case.ResultCloseFailure) {
                                        check(fault.resultFd != null && fault.resultCloses == 1)
                                    } else {
                                        check(fault.resultFd == null)
                                    }
                                    println("REJECTED case=$case cause=${failure.cause} result-fd=${fault.resultFd} quarantine-closes=${fault.resultCloses}")
                                }
                                Case.WrongRoot, Case.PartialHeader, Case.PartialPayload, Case.MissingComplete,
                                Case.ForbiddenPreopen, Case.KernelBackpressure,
                                -> error("Setup fault reached read")
                            }
                        }
                    }
                    check(session.confinedRetired && !requireNotNull(child).isAlive && creates == 1)
                    resources.validate()
                }
            } finally {
                if (child?.isAlive != true) plan.close()
            }
        } finally {
            if (child?.isAlive != true) resources.close()
            wrongRoot?.close()
        }
        check(child?.isAlive == false)
        if (childEffects) {
            check(Files.size(childLog) in 1..65536)
            val evidence = Files.readString(childLog)
            val expected = when (selected) {
                Case.ForbiddenPreopen -> "CHILD_FORBIDDEN_PREOPEN"
                Case.ForbiddenPinnedReopen -> "CHILD_PINNED_REOPEN"
                Case.ChildGc -> "CHILD_GC cleared=true"
                Case.KernelBackpressure -> "CHILD_BACKPRESSURE errno=11"
                Case.ResultCloseFailure -> "CHILD_RESULT_CLOSE errno=5"
                Case.Allowed, Case.RuntimeSupport, Case.WrongRoot, Case.WrongGeneration, Case.WrongPurpose,
                Case.LegacyOpen3, Case.LegacyAuthority4, Case.PartialHeader, Case.PartialPayload,
                Case.MissingComplete, Case.MissingExpected, Case.MissingReceived, Case.OwnerDeath,
                -> error("No child effect")
            }
            check(expected in evidence) { "Child effect did not run: $evidence" }
            println(evidence)
        }
        println("PASS case=$case child-retired=true creates=$creates")
    }

    private fun bounded(started: Long) {
        val elapsed = System.nanoTime() - started
        println("BOUNDED elapsed-ns=$elapsed maximum-ns=5250000000")
        check(elapsed <= 5_250_000_000L) { "Original transport budget exceeded: $elapsed" }
    }

    /** Only the outgoing native effect is altered; the actual child codec, reducer and ownership run. */
    private class WireFault(
        private val case: Case,
        private val real: NativeEngine,
    ) : NativeEngine by real {
        val setupFault = case in setOf(Case.WrongRoot, Case.PartialHeader, Case.PartialPayload, Case.MissingComplete, Case.ForbiddenPreopen, Case.KernelBackpressure)

        @Volatile var armed = setupFault
        var wrongRoot: Int? = null
        var injected = false
            private set
        private var stopReceiving = false
        var resultFd: Int? = null
            private set
        var resultCloses = 0
            private set
        override val fileSystem = object : NativeFileSystem by real.fileSystem {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                real.fileSystem.close(fd).also { if (fd.value == resultFd) resultCloses++ }
        }
        override val networking = object : NativeNetworking by real.networking {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (!armed) return real.networking.sendmsg(sockfd, msg, flags)
                if (injected && case in setOf(Case.PartialHeader, Case.PartialPayload)) return LinuxNative.SyscallResult.Error(11, -1)
                val header = MsghdrSegment(msg.native)
                val iov = IovecSegment(header.getMsgIov().reinterpret(Layouts.IOVEC.byteSize()))
                val size = iov.getIovLen()
                val data = iov.getIovBase().reinterpret(size)
                if (!injected && case == Case.WrongRoot && header.getMsgControllen() >= 20) {
                    val control = CmsghdrSegment(header.getMsgControl().reinterpret(header.getMsgControllen()))
                    val original = control.getDataFd()
                    control.setDataFd(requireNotNull(wrongRoot))
                    try {
                        return real.networking.sendmsg(sockfd, msg, flags).also { if (it is LinuxNative.SyscallResult.Success) injected = true }
                    } finally {
                        control.setDataFd(original)
                    }
                }
                if (!injected && size >= 15 && header.getMsgControllen() == 0L) {
                    val tag = data.get(ValueLayout.JAVA_BYTE, 0).toInt() and 255
                    if (tag == 0x43 && case == Case.ForbiddenPreopen) injected = true
                    if (tag == 0x43 && case == Case.KernelBackpressure) {
                        stopReceiving = true
                        injected = true
                    }
                    if (tag == 0x40 && case in setOf(Case.PartialHeader, Case.PartialPayload)) {
                        iov.setIovLen(if (case == Case.PartialHeader) 1 else 16)
                        try {
                            return real.networking.sendmsg(sockfd, msg, flags).also { if (it is LinuxNative.SyscallResult.Success) injected = true }
                        } finally {
                            iov.setIovLen(size)
                        }
                    }
                    if (case == Case.MissingComplete && tag == 0x43 || case == Case.MissingExpected && tag == 0x57 || case == Case.MissingReceived && tag == 0x58) {
                        injected = true
                        return LinuxNative.SyscallResult.Success(size)
                    }
                    if (tag == 0x41) {
                        val change = when (case) {
                            Case.LegacyOpen3 -> 0L to 3
                            Case.LegacyAuthority4 -> 0L to 4
                            Case.WrongGeneration -> 34L to 2
                            Case.WrongPurpose -> 139L to 2
                            Case.Allowed, Case.RuntimeSupport, Case.WrongRoot, Case.PartialHeader, Case.PartialPayload,
                            Case.MissingComplete, Case.MissingExpected, Case.MissingReceived, Case.ForbiddenPreopen,
                            Case.ForbiddenPinnedReopen, Case.ChildGc, Case.KernelBackpressure, Case.ResultCloseFailure, Case.OwnerDeath,
                            -> null
                        }
                        if (change != null) {
                            check(change.first < size)
                            data.set(ValueLayout.JAVA_BYTE, change.first, change.second.toByte())
                            injected = true
                        }
                    }
                }
                return real.networking.sendmsg(sockfd, msg, flags)
            }

            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (stopReceiving) return LinuxNative.SyscallResult.Error(11, -1)
                return real.networking.recvmsg(sockfd, msg, flags).also { result ->
                    val header = MsghdrSegment(msg.native)
                    if (armed && result is LinuxNative.SyscallResult.Success && result.value == 1L && header.getMsgControllen() >= 20) {
                        check(resultFd == null)
                        resultFd = CmsghdrSegment(header.getMsgControl().reinterpret(header.getMsgControllen())).getDataFd()
                    }
                }
            }
        }
    }

    /** Installs only native-effect faults, then enters the unmodified production Main interpreter. */
    internal object ChildEffects {
        @JvmStatic
        fun main(args: Array<String>) {
            val case = Case.valueOf(args[0])
            val forbidden = args[1]
            val real = LinuxNative.snapshot()
            val collectable = WeakReference(ByteArray(262144))
            var sealed = false
            var resultId = 0L
            var resultFd: Int? = null
            var forbiddenPin: Int? = null
            var backpressured = false
            val preopen = if (case == Case.ForbiddenPreopen) {
                NativeArena.ofConfined().use { arena ->
                SupervisorOwnedFd(
                    real.fileSystem.open(arena.allocateFrom(forbidden), OpenFlags(NativeConstants.O_RDONLY or NativeConstants.O_CLOEXEC)).getFdOrThrow("forbidden readable preopen"),
                    real.fileSystem,
                ).also { System.err.println("CHILD_FORBIDDEN_PREOPEN fd=${it.handle.value}") }
            }
            } else {
                null
            }
            val native = object : NativeEngine by real {
                override val fileSystem = object : NativeFileSystem by real.fileSystem {
                    override fun openat2(
                        dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                        path: ManagedSegment,
                        how: ManagedSegment,
                        size: Long,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        if (case != Case.ForbiddenPinnedReopen || path.readString(0) != "forbidden-probe") return real.fileSystem.openat2(dirfd, path, how, size)
                        check(sealed && forbiddenPin == null)
                        return NativeArena.ofConfined().use { arena ->
                            // A controlled wrong acquisition still has to pass the real kernel's readable reopen.
                            real.fileSystem.open(arena.allocateFrom(forbidden), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC)).also {
                                check(it is LinuxNative.SyscallResult.Success)
                                forbiddenPin = it.value.toInt()
                            }
                        }
                    }

                    override fun openat(
                        dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                        path: ManagedSegment,
                        flags: OpenFlags,
                        mode: Int,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                        real.fileSystem.openat(dirfd, path, flags, mode).also {
                            if (forbiddenPin != null && path.readString(0) == forbiddenPin.toString()) {
                                check(it is LinuxNative.SyscallResult.Error && it.errno in setOf(1, 13)) { "Forbidden pinned reopen succeeded: $it" }
                                System.err.println("CHILD_PINNED_REOPEN errno=${it.errno} pin=$forbiddenPin")
                            }
                        }

                    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        val closed = real.fileSystem.close(fd)
                        if (case == Case.ResultCloseFailure && fd.value == resultFd && resultId == 2L) {
                            check(closed is LinuxNative.SyscallResult.Success && !fd.isLiveForIo())
                            System.err.println("CHILD_RESULT_CLOSE errno=5 fd=${fd.value}")
                            return LinuxNative.SyscallResult.Error(5, -1)
                        }
                        return closed
                    }
                }
                override val networking = object : NativeNetworking by real.networking {
                    override fun sendmsg(
                        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                        msg: ManagedSegment,
                        flags: Int,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        val header = MsghdrSegment(msg.native)
                        val iov = IovecSegment(header.getMsgIov().reinterpret(Layouts.IOVEC.byteSize()))
                        val data = iov.getIovBase().reinterpret(iov.getIovLen())
                        if (header.getMsgControllen() >= 20) {
                            resultFd = CmsghdrSegment(header.getMsgControl().reinterpret(header.getMsgControllen())).getDataFd()
                        } else if (data.byteSize() >= 15) {
                            val tag = data.get(ValueLayout.JAVA_BYTE, 0).toInt() and 255
                            if (tag == 0x52) resultId = data.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 131)
                            if (tag == 0x50) {
                                sealed = true
                                if (case == Case.ChildGc) {
                                    System.gc()
                                    check(collectable.get() == null) { "Explicit child GC did not collect the fixture" }
                                    System.err.println("CHILD_GC cleared=true pid=${ProcessHandle.current().pid()}")
                                }
                                if (case == Case.KernelBackpressure && !backpressured) {
                                    NativeArena.ofConfined().use { arena ->
                                        val size = arena.allocate(4).also { it.writeInt(0, 4096) }
                                        real.raw
                                            .syscall(
                                            Arch.current().setsockopt.toLong(),
                                            NativeArg.FdArg(sockfd),
                                            NativeArg.IntArg(1),
                                            NativeArg.IntArg(7),
                                            NativeArg.MemoryArg(size),
                                            NativeArg.IntArg(4),
                                        ).getOrThrow("small helper send buffer")
                                    }
                                    var sent = 0L
                                    repeat(128) {
                                        when (val result = real.networking.sendmsg(sockfd, msg, flags)) {
                                            is LinuxNative.SyscallResult.Success -> sent += result.value
                                            is LinuxNative.SyscallResult.Error -> {
                                                check(result.errno == 11 && sent > 0)
                                                backpressured = true
                                                System.err.println("CHILD_BACKPRESSURE errno=11 queued-bytes=$sent")
                                                return result
                                            }
                                        }
                                    }
                                    error("Owned helper socket did not reach kernel backpressure")
                                }
                            }
                        }
                        return real.networking.sendmsg(sockfd, msg, flags)
                    }
                }
            }
            LinuxNative.setEngine(native)
            try {
                SocketHelperMain.main(args.drop(2).toTypedArray())
            } finally {
                preopen?.close()
            }
        }
    }

    private fun runtimeClasspath(
        directory: Path,
        childEffects: Boolean,
    ): List<Path> =
        (
        listOf(
        SocketHelperMain::class.java,
            LinuxNative::class.java,
            Unit::class.java,
        kotlinx.serialization.KSerializer::class.java,
            kotlinx.serialization.json.Json::class.java,
        kotlinx.coroutines.CoroutineScope::class.java,
            org.slf4j.LoggerFactory::class.java,
    ) +
        if (childEffects) listOf(javaClass) else emptyList()
    ).map {
        Path
            .of(
            it.protectionDomain.codeSource.location
        .toURI(),
        ).toRealPath()
    }.distinct()
            .mapIndexed { index, origin ->
        if (Files.isDirectory(origin)) {
            val jar = directory.resolve("runtime-$index.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                Files.walk(origin).use { files ->
                    files.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                        output.putNextEntry(JarEntry(origin.relativize(path).toString()).also { it.time = 0 })
                        Files.copy(path, output)
                        output.closeEntry()
                    }
                }
            }
            jar
        } else {
            origin
        }
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
