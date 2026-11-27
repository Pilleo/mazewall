package io.mazewall.lockdown

import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.lockdown.internal.ffi.NativeConstants
import io.mazewall.lockdown.internal.platform.sys.LinuxNative
import io.mazewall.lockdown.internal.platform.syscall.Arch
import io.mazewall.lockdown.internal.platform.syscall.NativeArg
import io.mazewall.lockdown.internal.platform.syscall.PrctlCommand
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@NeedsFreshJvm
@Tag("needs-fresh-jvm")
class MazewallLockdownIntegrationTest {
    private companion object {
        private const val LATCH_TIMEOUT_SECONDS = 10L
        private const val JIT_WARMUP_ITERATIONS = 20_000
        private const val GC_BUFFER_BYTES = 1024 * 1024
        private val ERRNO_EPERM_PATTERN = Regex("""\b(?:error|errno)[=:]\s*1\b""")
    }

    @Test
    fun `installOnProcess enforces process-wide EPERM across thread models while preserving JVM liveness`() {
        assertPreInstallProcessCreationWorks()
        assertPackagedSubprocessContracts()

        val threadReady = CountDownLatch(1)
        val installDone = CountDownLatch(1)
        val siblingFailure = AtomicReference<Throwable?>()
        val preExistingThread =
            Thread
                .ofPlatform()
                .name("pre-existing-platform-thread")
                .start {
                    threadReady.countDown()
                    try {
                        check(installDone.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            "Timed out waiting for lockdown installation"
                        }
                        assertProcessCreationDenied("pre-existing-platform-thread")
                    } catch (failure: Throwable) {
                        siblingFailure.set(failure)
                    }
                }

        val preExistingPool = Executors.newFixedThreadPool(1)
        try {
            val warmedName = preExistingPool.submit<String> { Thread.currentThread().name }.get()
            assertTrue(warmedName.isNotBlank())
            assertTrue(
                threadReady.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Pre-existing sibling thread should start before lockdown",
            )

            val installation = Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
            assertEquals(1, installation.filterDepth)
            assertTrue(installation.architecture.isNotBlank())

            val repeated = Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
            assertEquals(1, repeated.filterDepth)
            assertEquals(installation.architecture, repeated.architecture)

            installDone.countDown()

            assertProcessCreationDenied("main-thread")
            assertDirectKernelSyscallVerdicts()

            preExistingThread.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS))
            assertNull(siblingFailure.get(), "Pre-existing platform thread must fail with EPERM: ${siblingFailure.get()}")

            val poolOutcome =
                preExistingPool
                    .submit<Int> {
                        assertProcessCreationDenied("pre-existing-pool")
                        1
                    }.get()
            assertEquals(1, poolOutcome)

            assertNewPlatformThreadDenied()
            assertVirtualThreadDenied()
            assertPostInstallJvmLiveness()
        } finally {
            installDone.countDown()
            preExistingPool.shutdownNow()
        }
    }

    private fun assertDirectKernelSyscallVerdicts() {
        val arch = Arch.current()
        val zeroArgs = Array(6) { NativeArg.LongArg(0L) }

        fun invokeRaw(nr: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.raw.syscall(
                nr.toLong(),
                zeroArgs[0],
                zeroArgs[1],
                zeroArgs[2],
                zeroArgs[3],
                zeroArgs[4],
                zeroArgs[5],
            )

        val expectedEpermNrs =
            listOf(
                "execve" to arch.execve,
                "execveat" to arch.execveat,
                "memfd_create" to arch.memfdCreate,
                "io_uring_setup" to arch.ioUringSetup,
                "io_uring_enter" to arch.ioUringEnter,
            )
        expectedEpermNrs.forEach { (name, nr) ->
            val result = invokeRaw(nr)
            val err = assertIs<LinuxNative.SyscallResult.Error<*>>(result, "$name (nr=$nr) must fail under lockdown")
            assertEquals(NativeConstants.EPERM, err.errno, "$name (nr=$nr) must return EPERM")
        }

        val clone3Result = invokeRaw(arch.clone3)
        val clone3Err = assertIs<LinuxNative.SyscallResult.Error<*>>(clone3Result, "clone3 must return ENOSYS")
        assertEquals(NativeConstants.ENOSYS, clone3Err.errno, "clone3 must return ENOSYS")

        val disallowedPrctl = LinuxNative.process.prctl(PrctlCommand.SetPdeathsig(0L))
        val prctlErr = assertIs<LinuxNative.SyscallResult.Error<*>>(disallowedPrctl, "disallowed prctl must return EPERM")
        assertEquals(NativeConstants.EPERM, prctlErr.errno, "PR_SET_PDEATHSIG must return EPERM")

        val getSeccomp = LinuxNative.process.prctl(PrctlCommand.GetSeccomp)
        val mode = assertIs<LinuxNative.SyscallResult.Success<Long, *>>(getSeccomp, "PR_GET_SECCOMP must remain allowed")
        assertEquals(2L, mode.value)
    }

    private fun assertPackagedSubprocessContracts() {
        val lockdownJarProp = System.getProperty("mazewall.lockdown.jar")
        assertTrue(!lockdownJarProp.isNullOrBlank(), "mazewall.lockdown.jar property must be configured")
        val lockdownJar = Path.of(lockdownJarProp)
        assertTrue(Files.isRegularFile(lockdownJar), "Packaged JAR must exist: $lockdownJar")
        val stdlibLocation =
            KotlinVersion::class.java.protectionDomain.codeSource.location
                .toURI()
        val stdlibJar = Path.of(stdlibLocation)
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val javacBin = Path.of(System.getProperty("java.home"), "bin", "javac").toString()

        val tempDir = Files.createTempDirectory("mazewall-lockdown-subprocess-")
        try {
            val cpSrc = Files.createDirectories(tempDir.resolve("cp-src/probe"))
            val cpOut = Files.createDirectories(tempDir.resolve("cp-out"))
            val modSrc = Files.createDirectories(tempDir.resolve("mod-src"))
            val modProbeSrc = Files.createDirectories(modSrc.resolve("probe"))
            val modOut = Files.createDirectories(tempDir.resolve("mod-out"))

            val probeJava =
                """
                package probe;

                import io.mazewall.lockdown.LockdownPreset;
                import io.mazewall.lockdown.LockdownSyscall;
                import io.mazewall.lockdown.Mazewall;
                import io.mazewall.lockdown.MazewallInstallationException;
                import io.mazewall.lockdown.ProcessInstallation;
                import io.mazewall.lockdown.ProcessPolicy;
                import io.mazewall.lockdown.RuntimeProfile;
                import java.io.IOException;

                public final class Probe {
                    public static void main(String[] args) {
                        String mode = args[0];
                        try {
                            ProcessPolicy policy = "expect-elasticsearch-custom".equals(mode)
                                ? ProcessPolicy.fromPreset(LockdownPreset.ELASTICSEARCH, RuntimeProfile.HOTSPOT_JIT)
                                    .block(LockdownSyscall.PIDFD_GETFD)
                                    .unblock(LockdownSyscall.VFORK)
                                : ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT);
                            if ("expect-elasticsearch-custom".equals(mode)) {
                                if (!policy.blockedSyscalls().contains(LockdownSyscall.PIDFD_GETFD)
                                    || policy.blockedSyscalls().contains(LockdownSyscall.VFORK)) {
                                    throw new AssertionError("Unexpected blockedSyscalls: " + policy.blockedSyscalls());
                                }
                            }
                            ProcessInstallation inst = Mazewall.installOnProcess(policy);
                            if ("expect-install".equals(mode) || "expect-elasticsearch-custom".equals(mode)) {
                                try {
                                    new ProcessBuilder("/bin/echo", "blocked-probe").start();
                                    throw new AssertionError("ProcessBuilder unexpectedly succeeded after lockdown");
                                } catch (IOException expectedBlocked) {
                                    String msg = String.valueOf(expectedBlocked.getMessage());
                                    if (!msg.contains("Operation not permitted") && !msg.contains("error=1") && !msg.contains("errno=1")) {
                                        throw new AssertionError("Expected EPERM message, got: " + msg, expectedBlocked);
                                    }
                                }
                                System.out.println("INSTALLED:depth=" + inst.filterDepth());
                                return;
                            }
                            throw new AssertionError("Expected failure in mode=" + mode);
                        } catch (MazewallInstallationException expected) {
                            if ("expect-error".equals(mode)) {
                                System.out.println("CAUGHT_INSTALLATION_EX:" + expected.getMessage());
                                return;
                            }
                            throw expected;
                        }
                    }
                }
                """.trimIndent()
            Files.writeString(cpSrc.resolve("Probe.java"), probeJava)
            Files.writeString(modProbeSrc.resolve("Probe.java"), probeJava)
            Files.writeString(
                modSrc.resolve("module-info.java"),
                """
                module test.probe {
                    requires io.mazewall.lockdown;
                }
                """.trimIndent(),
            )

            val cpCompile =
                runCommand(
                    javacBin,
                    "--release",
                    "22",
                    "-cp",
                    "$lockdownJar:$stdlibJar",
                    "-d",
                    cpOut.toString(),
                    cpSrc.resolve("Probe.java").toString(),
                )
            assertEquals(0, cpCompile.first, "Classpath compile failed: ${cpCompile.second}")

            val modCompile =
                runCommand(
                    javacBin,
                    "--release",
                    "22",
                    "--module-path",
                    "$lockdownJar:$stdlibJar",
                    "-d",
                    modOut.toString(),
                    modSrc.resolve("module-info.java").toString(),
                    modProbeSrc.resolve("Probe.java").toString(),
                )
            assertEquals(0, modCompile.first, "JPMS module compile failed: ${modCompile.second}")

            val deniedNative =
                runCommand(
                    javaBin,
                    "--illegal-native-access=deny",
                    "-cp",
                    "$lockdownJar:$stdlibJar:$cpOut",
                    "probe.Probe",
                    "expect-error",
                )
            assertEquals(0, deniedNative.first, "Denied native access probe failed: ${deniedNative.second}")
            assertTrue(deniedNative.second.contains("CAUGHT_INSTALLATION_EX:"))
            assertFalse(deniedNative.second.contains("ExceptionInInitializerError"))

            val nonLinux =
                runCommand(
                    javaBin,
                    "-Dos.name=Mac OS X",
                    "-cp",
                    "$lockdownJar:$stdlibJar:$cpOut",
                    "probe.Probe",
                    "expect-error",
                )
            assertEquals(0, nonLinux.first, "Non-Linux probe failed: ${nonLinux.second}")
            assertTrue(nonLinux.second.contains("CAUGHT_INSTALLATION_EX:"))
            assertFalse(nonLinux.second.contains("WARNING: A restricted method in java.lang.foreign.Linker has been called"))

            val jpmsRun =
                runCommand(
                    javaBin,
                    "--module-path",
                    "$lockdownJar:$stdlibJar:$modOut",
                    "--enable-native-access=io.mazewall.lockdown",
                    "-m",
                    "test.probe/probe.Probe",
                    "expect-install",
                )
            assertEquals(0, jpmsRun.first, "JPMS modular lockdown install failed: ${jpmsRun.second}")
            assertTrue(jpmsRun.second.contains("INSTALLED:depth=1"))
            assertFalse(jpmsRun.second.contains("WARNING: A restricted method"))

            val optInSelfVerify =
                runCommand(
                    javaBin,
                    "--enable-native-access=ALL-UNNAMED",
                    "-Dio.mazewall.selfVerify=true",
                    "-cp",
                    "$lockdownJar:$stdlibJar:$cpOut",
                    "probe.Probe",
                    "expect-install",
                )
            assertEquals(0, optInSelfVerify.first, "Opt-in selfVerify probe failed: ${optInSelfVerify.second}")
            assertTrue(optInSelfVerify.second.contains("INSTALLED:depth=1"))

            val elasticCustom =
                runCommand(
                    javaBin,
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    "$lockdownJar:$stdlibJar:$cpOut",
                    "probe.Probe",
                    "expect-elasticsearch-custom",
                )
            assertEquals(0, elasticCustom.first, "Customized Elasticsearch preset probe failed: ${elasticCustom.second}")
            assertTrue(elasticCustom.second.contains("INSTALLED:depth=1"))

            val incompatibleJvmFlag =
                runCommand(
                    javaBin,
                    "--enable-native-access=ALL-UNNAMED",
                    "-XX:OnOutOfMemoryError=/bin/true",
                    "-cp",
                    "$lockdownJar:$stdlibJar:$cpOut",
                    "probe.Probe",
                    "expect-error",
                )
            assertEquals(0, incompatibleJvmFlag.first, "Incompatible JVM flag probe failed: ${incompatibleJvmFlag.second}")
            assertTrue(incompatibleJvmFlag.second.contains("CAUGHT_INSTALLATION_EX:"))
            assertTrue(incompatibleJvmFlag.second.contains("-XX:OnOutOfMemoryError=/bin/true"))
            assertTrue(incompatibleJvmFlag.second.contains("-XX:+ExitOnOutOfMemoryError"))

            val appArgNotFlag =
                runCommand(
                    javaBin,
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    "$lockdownJar:$stdlibJar:$cpOut",
                    "probe.Probe",
                    "expect-install",
                    "-XX:OnOutOfMemoryError=/bin/true",
                )
            assertEquals(0, appArgNotFlag.first, "App argument after main class must not trigger JVM flag rejection: ${appArgNotFlag.second}")
            assertTrue(appArgNotFlag.second.contains("INSTALLED:depth=1"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun runCommand(vararg args: String): Pair<Int, String> {
        val process =
            ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
        assertTrue(process.waitFor(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Subprocess timed out: ${args.toList()}")
        return process.exitValue() to output
    }

    private fun assertPreInstallProcessCreationWorks() {
        val process = ProcessBuilder("/bin/echo", "pre-install-ok").start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        assertEquals(0, process.waitFor())
        assertEquals("pre-install-ok", output)
    }

    private fun assertNewPlatformThreadDenied() {
        val threadFailure = AtomicReference<Throwable?>()
        val newThread =
            Thread
                .ofPlatform()
                .name("post-install-platform-thread")
                .start {
                    try {
                        assertProcessCreationDenied("post-install-platform-thread")
                    } catch (failure: Throwable) {
                        threadFailure.set(failure)
                    }
                }
        newThread.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS))
        assertNull(threadFailure.get(), "New platform thread must fail with EPERM: ${threadFailure.get()}")
    }

    private fun assertVirtualThreadDenied() {
        Executors.newVirtualThreadPerTaskExecutor().use { virtualExecutor ->
            val outcome =
                virtualExecutor
                    .submit<Int> {
                        assertTrue(Thread.currentThread().isVirtual)
                        assertProcessCreationDenied("virtual-thread")
                        2
                    }.get()
            assertEquals(2, outcome)
        }
    }

    private fun assertProcessCreationDenied(context: String) {
        val builderFailure =
            assertFailsWith<IOException>("ProcessBuilder should fail with EPERM in $context") {
                ProcessBuilder("/bin/echo", context).start()
            }
        assertAuthenticatedEperm(builderFailure, "ProcessBuilder ($context)")

        @Suppress("DEPRECATION")
        val runtimeFailure =
            assertFailsWith<IOException>("Runtime.exec should fail with EPERM in $context") {
                Runtime.getRuntime().exec(arrayOf("/bin/echo", context))
            }
        assertAuthenticatedEperm(runtimeFailure, "Runtime.exec ($context)")
    }

    private fun assertAuthenticatedEperm(
        failure: Throwable,
        operation: String,
    ) {
        val messages =
            generateSequence(failure) { it.cause }
                .mapNotNull { it.message }
                .toList()
        val matched =
            messages.any { msg ->
                msg.contains("Operation not permitted", ignoreCase = true) ||
                    ERRNO_EPERM_PATTERN.containsMatchIn(msg)
            }
        assertTrue(
            matched,
            "$operation failed without authenticated EPERM / Operation not permitted in cause chain: $messages",
        )
    }

    private fun assertPostInstallJvmLiveness() {
        // 1. JIT warmup loop
        var checksum = 1L
        for (i in 1..JIT_WARMUP_ITERATIONS) {
            checksum = (checksum * 31L + i) xor (checksum ushr 7)
        }
        assertTrue(checksum != 0L)

        // 2. Heap allocation + GC
        @Suppress("ExplicitGarbageCollectionCall")
        val retained = ByteArray(GC_BUFFER_BYTES) { it.toByte() }
        assertEquals(GC_BUFFER_BYTES, retained.size)
        System.gc()

        // 3. Dynamic classloading
        URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader()).use { isolatedLoader ->
            val loaded = Class.forName("java.lang.StringBuilder", false, isolatedLoader)
            val instance = loaded.getDeclaredConstructor().newInstance() as StringBuilder
            instance.append("alive")
            assertEquals("alive", instance.toString())
        }

        // 4. Temp file I/O
        val tempFile = Files.createTempFile("mazewall-lockdown-", ".txt")
        try {
            Files.writeString(tempFile, "ok")
            assertEquals("ok", Files.readString(tempFile))
        } finally {
            Files.deleteIfExists(tempFile)
        }

        // 5. Loopback socket exchange
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = AtomicReference<String>()
            val acceptor =
                Thread
                    .ofPlatform()
                    .name("loopback-acceptor")
                    .start {
                        server.accept().use { client ->
                            val line =
                                client
                                    .getInputStream()
                                    .bufferedReader()
                                    .readLine()
                            received.set(line)
                        }
                    }
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                client.getOutputStream().write("ping\n".toByteArray())
                client.getOutputStream().flush()
            }
            acceptor.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS))
            assertEquals("ping", received.get())
        }
    }
}
