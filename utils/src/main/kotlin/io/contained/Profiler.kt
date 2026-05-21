package io.contained

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * High-performance Out-of-Process USER_NOTIF Profiler API.
 */
object Profiler {
    private val logger = Logger.getLogger(Profiler::class.java.name)
    private val pathCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Captured syscall trace events.
     */
    val recentLogs = java.util.concurrent.CopyOnWriteArrayList<TraceEvent>()

    /**
     * Compiles the currently profiled logs into a live Policy object.
     */
    fun compilePolicy(basePolicy: Policy = Policy.PURE_COMPUTE): Policy {
        return BobCompiler.compile(recentLogs, basePolicy)
    }

    /**
     * Compiles the currently profiled logs into a beautiful copy-pasteable Kotlin DSL string.
     */
    fun compileToDsl(basePolicyName: String = "Policy.PURE_COMPUTE"): String {
        return BobCompiler.compileToDsl(recentLogs, basePolicyName)
    }

    /**
     * Clears all accumulated profiling logs and path caches.
     */
    fun clear() {
        recentLogs.clear()
        pathCache.clear()
    }

    fun wrap(delegate: ExecutorService, vararg policies: Policy): ExecutorService {
        val policy = Policy.combine(*policies)

        // Setup socket path
        val tempDir = File("build/tmp")
        tempDir.mkdirs()
        val socketFile = File.createTempFile("jseccomp-profiler-", ".sock", tempDir)
        socketFile.delete() // delete so bind can create it
        val socketPath = socketFile.absolutePath

        // Spawn Daemon
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val pb = ProcessBuilder(
            javaBin,
            "--enable-native-access=ALL-UNNAMED",
            "-cp", classpath,
            "io.contained.ProfilerDaemon",
            socketPath
        )
        val daemonProcess = pb.start()
        val daemonPid = daemonProcess.pid()

        // Set the daemon process as our allowed ptrace tracer under Yama ptrace_scope = 1
        // PR_SET_PTRACER = 0x59616d61
        LinuxNative.prctl(0x59616d61, daemonPid, 0, 0, 0)

        // Stream daemon errorStream to System.err
        Thread {
            daemonProcess.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    System.err.println("[DAEMON ERR] $line")
                    System.err.flush()
                }
            }
        }.apply { isDaemon = true; name = "profiler-daemon-stderr" }.start()

        // Ensure daemon process is cleaned up on JVM exit
        Runtime.getRuntime().addShutdownHook(Thread {
            daemonProcess.destroyForcibly()
        })

        return ProfilerExecutorWrapper(delegate, policy, socketPath, daemonProcess)
    }

    private fun connectWithRetry(socketPath: String, maxRetries: Int = 30, delayMs: Long = 100): Int {
        Arena.ofConfined().use { arena ->
            val addr = arena.allocate(LinuxNative.SOCKADDR_UN_LAYOUT)
            addr.fill(0)
            addr.set(ValueLayout.JAVA_SHORT, 0L, 1.toShort()) // AF_UNIX = 1

            val pathBytes = socketPath.toByteArray(Charsets.UTF_8)
            val pathSeg = addr.asSlice(2, 108)
            for (i in pathBytes.indices) {
                pathSeg.set(ValueLayout.JAVA_BYTE, i.toLong(), pathBytes[i])
            }

            var lastErrno = 0
            for (retry in 0 until maxRetries) {
                val fdRes = LinuxNative.socket(1 /* AF_UNIX */, 1 /* SOCK_STREAM */, 0)
                if (fdRes.returnValue < 0) {
                    throw IllegalStateException("Failed to create socket: errno=${fdRes.errno}")
                }
                val fd = fdRes.returnValue.toInt()
                val connRes = LinuxNative.connect(fd, addr, 110)
                if (connRes.returnValue == 0L) {
                    return fd
                }
                lastErrno = connRes.errno
                LinuxNative.close(fd)

                Thread.sleep(delayMs)
            }
            throw IllegalStateException("Failed to connect to daemon socket at $socketPath after $maxRetries retries. Last errno=$lastErrno")
        }
    }

    private fun sendDescriptor(socketFd: Int, fdToSend: Int): Boolean {
        Arena.ofConfined().use { arena ->
            val dummyByte = arena.allocate(ValueLayout.JAVA_BYTE)
            dummyByte.set(ValueLayout.JAVA_BYTE, 0, 0.toByte())

            val iov = arena.allocate(LinuxNative.IOVEC_LAYOUT)
            iov.set(ValueLayout.ADDRESS, 0L, dummyByte)
            iov.set(ValueLayout.JAVA_LONG, 8L, 1L)

            val controlBuf = arena.allocate(24)
            controlBuf.fill(0)
            controlBuf.set(ValueLayout.JAVA_LONG, 0L, 20L) // cmsg_len
            controlBuf.set(ValueLayout.JAVA_INT, 8L, 1)    // cmsg_level (SOL_SOCKET = 1)
            controlBuf.set(ValueLayout.JAVA_INT, 12L, 1)   // cmsg_type (SCM_RIGHTS = 1)
            controlBuf.set(ValueLayout.JAVA_INT, 16L, fdToSend)

            val msg = arena.allocate(LinuxNative.MSGHDR_LAYOUT)
            msg.fill(0)
            msg.set(ValueLayout.ADDRESS, 16L, iov)
            msg.set(ValueLayout.JAVA_LONG, 24L, 1L)
            msg.set(ValueLayout.ADDRESS, 32L, controlBuf)
            msg.set(ValueLayout.JAVA_LONG, 40L, 24L)

            val res = LinuxNative.sendmsg(socketFd, msg, 0)
            return res.returnValue >= 0
        }
    }

    internal class ProfilerExecutorWrapper(
        private val delegate: ExecutorService,
        private val policy: Policy,
        private val socketPath: String,
        private val daemonProcess: Process
    ) : ExecutorService by delegate {

        private val threadApplied = ThreadLocal.withInitial { false }

        override fun execute(command: Runnable) {
            delegate.execute {
                ensureApplied()
                command.run()
            }
        }

        override fun <T> submit(task: Callable<T>): Future<T> =
            delegate.submit(Callable {
                ensureApplied()
                task.call()
            })

        override fun <T> submit(task: Runnable, result: T): Future<T> =
            delegate.submit({
                ensureApplied()
                task.run()
            }, result)

        override fun submit(task: Runnable): Future<*> =
            delegate.submit {
                ensureApplied()
                task.run()
            }

        private fun ensureApplied() {
            if (Thread.currentThread().isVirtual) {
                throw IllegalStateException("Seccomp profiling is not supported on Loom virtual threads.")
            }
            if (!threadApplied.get()) {
                installProfilingFilter()

                // Landlock Audit is non-transparent (denies and logs).
                // Only enable if explicitly requested for io_uring profiling.
                if (System.getenv("JSECCOMP_PROFILER_AUDIT") == "true") {
                    Landlock.applyProfilingRuleset()
                }

                threadApplied.set(true)
            }
        }

        private fun installProfilingFilter() {
            val r1 = LinuxNative.prctl(LinuxNative.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)
            if (r1.returnValue != 0L) {
                throw IllegalStateException("prctl(PR_SET_NO_NEW_PRIVS) failed with errno ${r1.errno}")
            }

            val arch = Arch.current()
            // Unblock bootstrap syscalls to prevent chicken-and-egg deadlocks during socket connection exfiltration
            val profilingPolicy = Policy.builder()
                .base(policy)
                .unblock(Syscall.SOCKET, Syscall.CONNECT, Syscall.SENDMSG, Syscall.SENDTO)
                .build()
            val filters = BpfFilter.build(arch, profilingPolicy, profilingMode = true)

            Arena.ofConfined().use { arena ->
                val prog = LinuxNative.newSockFProg(arena, filters)

                val r = LinuxNative.syscall(
                    arch.seccompSyscallNumber.toLong(),
                    LinuxNative.SECCOMP_SET_MODE_FILTER.toLong(),
                    LinuxNative.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong(),
                    prog
                )

                if (r.returnValue < 0) {
                    throw IllegalStateException("Failed to install seccomp profiling listener: errno=${r.errno}")
                }

                val listenerFd = r.returnValue.toInt()

                val socketFd = connectWithRetry(socketPath)
                try {
                    val sent = sendDescriptor(socketFd, listenerFd)
                    if (!sent) {
                        throw IllegalStateException("Failed to send seccomp listener FD to daemon")
                    }

                    // Wait for ACK byte from daemon
                    Arena.ofConfined().use { arena ->
                        val ackBuf = arena.allocate(1)
                        val res = LinuxNative.read(socketFd, ackBuf, 1)
                        if (res.returnValue != 1L || ackBuf.get(ValueLayout.JAVA_BYTE, 0) != 0xAC.toByte()) {
                            throw IllegalStateException("Daemon failed to ACK listener receipt")
                        }
                    }

                    // Start listener thread for this socket to receive TraceEvents
                    startTraceListener(socketFd)
                } catch (e: Exception) {
                    LinuxNative.close(socketFd)
                    throw e
                } finally {
                    LinuxNative.close(listenerFd)
                }
            }
        }

        private fun startTraceListener(socketFd: Int) {
            val inputStream = object : InputStream() {
                override fun read(): Int {
                    Arena.ofConfined().use { arena ->
                        val b = arena.allocate(1)
                        val res = LinuxNative.read(socketFd, b, 1)
                        if (res.returnValue <= 0) return -1
                        return b.get(ValueLayout.JAVA_BYTE, 0L).toInt() and 0xFF
                    }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    Arena.ofConfined().use { arena ->
                        val buf = arena.allocate(len.toLong())
                        val res = LinuxNative.read(socketFd, buf, len.toLong())
                        if (res.returnValue <= 0) return -1
                        val readLen = res.returnValue.toInt()
                        for (i in 0 until readLen) {
                            b[off + i] = buf.get(ValueLayout.JAVA_BYTE, i.toLong())
                        }
                        return readLen
                    }
                }

                override fun close() {
                    LinuxNative.close(socketFd)
                }
            }

            Thread {
                try {
                    val dis = DataInputStream(inputStream)
                    while (true) {
                        val pid = dis.readInt()
                        val syscallNameLen = dis.readInt()
                        val syscallNameBytes = ByteArray(syscallNameLen)
                        dis.readFully(syscallNameBytes)
                        val syscallName = String(syscallNameBytes, Charsets.UTF_8)

                        val argsCount = dis.readInt()
                        val args = LongArray(argsCount)
                        for (i in 0 until argsCount) {
                            args[i] = dis.readLong()
                        }

                        val pathsCount = dis.readInt()
                        val paths = mutableListOf<String>()
                        for (i in 0 until pathsCount) {
                            val pathLen = dis.readInt()
                            val pathBytes = ByteArray(pathLen)
                            dis.readFully(pathBytes)
                            paths.add(String(pathBytes, Charsets.UTF_8))
                        }

                        val event = TraceEvent(pid, syscallName, args, paths)

                        // Deduplicate synchronous events that trigger both Seccomp and Landlock Audit
                        if (paths.isNotEmpty()) {
                            val cacheKey = "$syscallName:${paths.sorted().joinToString(",")}"
                            val now = System.currentTimeMillis()
                            val lastSeen = pathCache.get(cacheKey) ?: 0L
                            if (now - lastSeen < 500) {
                                println("[PROFILER] Deduplicated duplicate event for $cacheKey")
                                continue // Skip duplicate within 500ms window
                            }
                            pathCache[cacheKey] = now
                        }

                        recentLogs.add(event)
                    }
                } catch (e: Exception) {
                    // socket closed or error
                } finally {
                    inputStream.close()
                }
            }.apply { isDaemon = true; name = "trace-listener-$socketFd" }.start()
        }

        override fun shutdown() {
            try {
                // Graceful cleanup
            } finally {
                delegate.shutdown()
                daemonProcess.destroy()
                pathCache.clear()
            }
        }

        override fun shutdownNow(): List<Runnable> {
            val tasks = delegate.shutdownNow()
            daemonProcess.destroyForcibly()
            pathCache.clear()
            return tasks
        }
    }

    private fun Long.asMemorySegment(): MemorySegment = MemorySegment.ofAddress(this)
}
