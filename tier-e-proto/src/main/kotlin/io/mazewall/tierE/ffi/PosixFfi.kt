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
    private val hMunmap: MethodHandle = bind(
        "munmap",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    )

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
            val slice = MemorySegment.ofArray(bytes).asSlice(sent.toLong(), (bytes.size - sent).toLong())
            val n = hSend.invoke(fd, slice, slice.byteSize(), MSG_NOSIGNAL) as Long
            if (n <= 0L) return false
            sent += n.toInt()
        }
        return true
    }

    /** Reads up to [len] bytes at [offset]; returns count, 0 on EOF, negative -errno. */
    public fun recv(fd: Int, into: ByteArray, offset: Int = 0, len: Int = into.size - offset): Int =
        hRecv.invoke(
            fd,
            MemorySegment.ofArray(into).asSlice(offset.toLong(), len.toLong()),
            len.toLong(),
            0,
        ) as Int

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

    /** Blocking accept4(SOCK_CLOEXEC). Returns -EINTR-shaped negative on error. */
    public fun accept(lfd: Int): Int {
        val rc = hAccept4.invoke(lfd, MemorySegment.NULL, MemorySegment.NULL, 0x80000) as Int
        return if (rc >= 0) rc else -errnoGuess()
    }

    private fun errnoGuess(): Int = -1 // precise errno capture lands with WP-14

    public fun close(fd: Int) {
        hClose.invoke(fd)
    }

    /** PROT_READ|PROT_WRITE, MAP_SHARED. Returns MAP_FAILED (-1) as NULL+1? No:
     *  returns the mapping or throws when the kernel maps failure to -1. */
    public fun mmapShared(length: Long, fd: Int): MemorySegment {
        val seg = hMmap.invoke(MemorySegment.NULL, length, 3, 1, fd, 0L) as MemorySegment
        check(seg != MemorySegment.NULL && seg.address() != -1L) { "mmap failed" }
        return seg
    }

    public fun munmap(segment: MemorySegment, length: Long) {
        hMunmap.invoke(segment, length)
    }

    private companion object {
        const val MSG_NOSIGNAL = 0x4000
    }
}
