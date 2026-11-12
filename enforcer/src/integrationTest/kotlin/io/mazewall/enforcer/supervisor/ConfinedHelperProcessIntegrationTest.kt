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

    private fun verifyCase(case: String) {
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
                    SocketHelperProcess
                        .startConfined(classpath.joinToString(File.pathSeparator)) { builder ->
                        if (childEffects) {
                            val command = builder.command()
                            val main = command.indexOf(SocketHelperMain::class.java.name)
                            check(main > 0)
                            command[main] = ChildEffects::class.java.name
                            command.addAll(main + 1, listOf(selected.name, outside.toString()))
                            builder.redirectError(childLog.toFile())
                        }
                        builder.start()
                    }.also { child = it.processHandle }
                }.use { pool ->
                    pool.prewarm()
                    check(creates == 1 && requireNotNull(child).isAlive)
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
