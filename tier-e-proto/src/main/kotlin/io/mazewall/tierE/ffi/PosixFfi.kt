package io.mazewall.tierE.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/** Peer credentials as reported by SO_PEERCRED (Linux). */
public data class Ucred(public val pid: Int, public val uid: Int, public val gid: Int)

/**
 * Small libc surface the Kotlin control plane owns directly (kept out of
 * :platform until WP-14 decides what migrates there): SO_PEERCRED, raw
 * send/recv/close on AF_UNIX fds, mmap/munmap for the BPF ring buffer.
 */
public class PosixFfi {
    private val arena: Arena = Arena.ofShared()
    private val lookup: SymbolLookup = SymbolLookup.libraryLookup("libc.so.6", arena)
    private val linker: Linker = Linker.nativeLinker()

    private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            IllegalStateException("libc symbol $name not found")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    private val hGetsockopt: MethodHandle = bind(
        "getsockopt",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )
    private val hSend: MethodHandle = bind(
        "send",
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
        ),
    )
    private val hRecv: MethodHandle = bind(
        "recv",
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
        ),
    )
    private val hGettid: MethodHandle = bind(
        "gettid",
        FunctionDescriptor.of(ValueLayout.JAVA_INT),
    )
    private val hGetpid: MethodHandle = bind(
        "getpid",
        FunctionDescriptor.of(ValueLayout.JAVA_INT),
    )
    private val hSetsockopt: MethodHandle = bind(
        "setsockopt",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )
    private val hClose: MethodHandle = bind(
        "close",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val hMmap: MethodHandle = bind(
        "mmap",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
        ),
    )
    private val hErrnoLocation: MethodHandle = bind(
        "__errno_location",
        FunctionDescriptor.of(ValueLayout.ADDRESS),
    )
    private val hMunmap: MethodHandle = bind(
        "munmap",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    )

    /** Downcalls reject heap segments: all byte traffic stages here. */
    private val nativeScratch: MemorySegment = arena.allocate(64L * 1024)
    private val scratchSize: Long = 64L * 1024

    /** SOL_SOCKET=1, SO_PEERCRED=17 (Linux). Null when creds are missing. */
    public fun peerCredentials(fd: Int): Ucred? {
        val buf = arena.allocate(12)
        val lenPtr = arena.allocate(ValueLayout.JAVA_INT)
        lenPtr.set(ValueLayout.JAVA_INT, 0, 12)
        val rc = hGetsockopt.invoke(fd, 1, 17, buf, lenPtr) as Int
        if (rc != 0 || lenPtr.get(ValueLayout.JAVA_INT, 0) != 12) return null
        return Ucred(
            pid = buf.get(ValueLayout.JAVA_INT, 0),
            uid = buf.get(ValueLayout.JAVA_INT, 4),
            gid = buf.get(ValueLayout.JAVA_INT, 8),
        )
    }

    public fun sendAll(fd: Int, bytes: ByteArray): Boolean {
        var sent = 0
        while (sent < bytes.size) {
            val chunk = minOf((bytes.size - sent).toLong(), scratchSize).toInt()
            java.lang.foreign.MemorySegment.copy(
                MemorySegment.ofArray(bytes), sent.toLong(),
                nativeScratch, 0L, chunk.toLong(),
            )
            val n = hSend.invoke(fd, nativeScratch, chunk.toLong(), MSG_NOSIGNAL) as Long
            if (n <= 0L) return false
            sent += n.toInt()
        }
        return true
    }

    /** Reads up to [len] bytes at [offset]; returns count, 0 on EOF, negative -errno. */
    public fun recv(fd: Int, into: ByteArray, offset: Int = 0, len: Int = into.size - offset): Int {
        val capped = minOf(len.toLong(), scratchSize).toInt()
        val n = (hRecv.invoke(
            fd,
            nativeScratch,
            capped.toLong(),
            0,
        ) as Long).toInt()
        if (n > 0) {
            java.lang.foreign.MemorySegment.copy(
                nativeScratch, 0L,
                MemorySegment.ofArray(into), offset.toLong(), n.toLong(),
            )
        }
        return n
    }

    private val hSocket: MethodHandle = bind(
        "socket",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val hBind: MethodHandle = bind(
        "bind",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val hListen: MethodHandle = bind(
        "listen",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val hConnect: MethodHandle = bind(
        "connect",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val hAccept4: MethodHandle = bind(
        "accept4",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )

    /** AF_UNIX=1, SOCK_STREAM=1. Binds [path] (stale file unlinked) and listens. */
    public fun listenUnix(path: String): Int {
        val fd = hSocket.invoke(1, 1, 0) as Int
        if (fd < 0) return -errnoGuess()
        Files.deleteIfExists(Path.of(path))
        val addr = arena.allocate(110)
        addr.set(ValueLayout.JAVA_SHORT, 0, 1) // sun_family = AF_UNIX
        val bytes = path.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 106) { "socket path exceeds sockaddr_un" }
        for (i in bytes.indices) addr.set(ValueLayout.JAVA_BYTE, 2L + i, bytes[i])
        addr.set(ValueLayout.JAVA_BYTE, (2 + bytes.size).toLong(), 0)
        if ((hBind.invoke(fd, addr, 110) as Int) != 0) return -errnoGuess()
        if ((hListen.invoke(fd, 2) as Int) != 0) return -errnoGuess()
        return fd
    }

    /** Connects and immediately closes — used to wake a blocked accept(). */
    public fun connectUnix(path: String): Int {
        val fd = hSocket.invoke(1, 1, 0) as Int
        if (fd < 0) return fd
        val addr = arena.allocate(110)
        addr.set(ValueLayout.JAVA_SHORT, 0, 1)
        val bytes = path.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 106)
        for (i in bytes.indices) addr.set(ValueLayout.JAVA_BYTE, 2L + i, bytes[i])
        addr.set(ValueLayout.JAVA_BYTE, (2 + bytes.size).toLong(), 0)
        val rc = hConnect.invoke(fd, addr, 110) as Int
        return if (rc != 0) {
            close(fd)
            -currentErrno()
        } else {
            fd
        }
    }

    /** Blocking accept4(SOCK_CLOEXEC). Returns -EINTR-shaped negative on error. */
    public fun accept(lfd: Int): Int {
        val rc = hAccept4.invoke(lfd, MemorySegment.NULL, MemorySegment.NULL, 0x80000) as Int
        return if (rc >= 0) rc else -errnoGuess()
    }

    private fun errnoGuess(): Int = -1 // precise errno capture lands with WP-14

    /** Triggers syscall getpid(39) — used by stress workers as an attributable
     *  no-side-effect syscall. */
    public fun getpid(): Int = hGetpid.invoke() as Int

    /** Returns the Linux TID (kernel task id) of the calling thread.
     *  MUST be used instead of Thread.threadId() for BPF event correlation. */
    public fun gettid(): Int = hGettid.invoke() as Int

    /** Sets SO_RCVTIMEO so blocking recv() returns -1/EAGAIN after [timeoutMs]. */
    public fun setRecvTimeout(fd: Int, timeoutMs: Int) {
        val tv = arena.allocate(16)
        tv.set(ValueLayout.JAVA_LONG, 0, timeoutMs / 1000L)
        tv.set(ValueLayout.JAVA_LONG, 8, (timeoutMs % 1000L) * 1_000L)
        val lenPtr = arena.allocate(ValueLayout.JAVA_INT)
        lenPtr.set(ValueLayout.JAVA_INT, 0, 16)
        hSetsockopt.invoke(fd, 1 /* SOL_SOCKET */, 20 /* SO_RCVTIMEO */, tv, lenPtr)
    }

    public fun close(fd: Int) {
        hClose.invoke(fd)
    }

    public fun currentErrno(): Int {
        // Returned-pointer segments are zero-length; widen before reading.
        val p = (hErrnoLocation.invoke() as MemorySegment).reinterpret(4)
        return p.get(ValueLayout.JAVA_INT, 0)
    }

    /** MAP_SHARED with explicit prot/offset. Throws with errno on failure.
     *  Returned-pointer segments are zero-length; widen to the mapping size. */
    public fun mmapShared(length: Long, fd: Int, prot: Int = PROT_READ or PROT_WRITE, offset: Long = 0L): MemorySegment {
        val seg = hMmap.invoke(MemorySegment.NULL, length, prot, 1, fd, offset) as MemorySegment
        if (seg == MemorySegment.NULL || seg.address() == -1L) {
            throw IllegalStateException(
                "mmap failed: len=$length fd=$fd prot=$prot off=$offset errno=${currentErrno()}",
            )
        }
        return seg.reinterpret(length)
    }

    public companion object {
        public const val PROT_READ: Int = 1
        public const val PROT_WRITE: Int = 2
        private const val MSG_NOSIGNAL = 0x4000
    }

    public fun munmap(segment: MemorySegment, length: Long) {
        hMunmap.invoke(segment, length)
    }
}
