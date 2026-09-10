package io.mazewall.ffi.internal

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.native

/** Checks bounded local memory before its address or a size_t crosses the FFM boundary. */
internal object NativeTransfer {
    fun ioctl(
        request: Long,
        segment: ManagedSegment,
    ) {
        val encodedSize = (request ushr 16) and 0x3fffL
        // Legacy FIONREAD/TIOCINQ predates _IOC and writes a C int.
        val size = if (request == 0x541bL) 4L else encodedSize
        require(size > 0) { "Managed ioctl request has no known payload size: $request" }
        bytes(segment, size, writable = true)
    }

    fun bytes(
        segment: ManagedSegment,
        count: Long = segment.byteSize(),
        writable: Boolean = false,
    ) {
        require(count >= 0 && count <= segment.byteSize()) { "Native transfer count $count exceeds segment size ${segment.byteSize()}" }
        val native = segment.native
        check(native.scope().isAlive) { "Native transfer segment is closed" }
        check(native.isAccessibleBy(Thread.currentThread())) { "Native transfer segment belongs to another thread" }
        require(native.isNative) { "Native transfer requires native memory" }
        require(count == 0L || native.address() != 0L) { "Native transfer requires a non-null buffer" }
        require(!writable || !native.isReadOnly) { "Native output buffer is read-only" }
    }

    fun elements(
        segment: ManagedSegment,
        count: Long,
        elementSize: Long,
        writable: Boolean = false,
    ) {
        require(count >= 0 && count <= segment.byteSize() / elementSize) { "Native element count $count exceeds segment capacity" }
        bytes(segment, count * elementSize, writable)
    }

    fun path(segment: ManagedSegment) {
        bytes(segment)
        require(segment.byteSize() > 0) { "Native path is empty" }
        var offset = 0L
        while (offset < segment.byteSize()) {
            if (segment.native.get(java.lang.foreign.ValueLayout.JAVA_BYTE, offset) == 0.toByte()) return
            offset++
        }
        throw IllegalArgumentException("Native path has no terminator")
    }

    fun socketAddress(
        address: ManagedSegment,
        length: ManagedSegment,
    ) {
        if (address.address() == 0L) return
        bytes(length, 4, writable = true)
        val count = Integer.toUnsignedLong(length.native.get(java.lang.foreign.ValueLayout.JAVA_INT, 0))
        bytes(address, count, writable = true)
    }
}
