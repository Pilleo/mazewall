package io.mazewall.ffi.memory

import io.mazewall.ffi.Layouts
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Type-safe wrapper for `struct sock_filter`.
 */
@JvmInline
public value class SockFilterSegment(public val segment: MemorySegment) {
    public fun getCode(): Short = segment.get(ValueLayout.JAVA_SHORT, Layouts.SOCK_FILTER_CODE_OFFSET)
    public fun setCode(value: Short): Unit {
        segment.set(ValueLayout.JAVA_SHORT, Layouts.SOCK_FILTER_CODE_OFFSET, value)
    }

    public fun getJt(): Byte = segment.get(ValueLayout.JAVA_BYTE, Layouts.SOCK_FILTER_JT_OFFSET)
    public fun setJt(value: Byte): Unit {
        segment.set(ValueLayout.JAVA_BYTE, Layouts.SOCK_FILTER_JT_OFFSET, value)
    }

    public fun getJf(): Byte = segment.get(ValueLayout.JAVA_BYTE, Layouts.SOCK_FILTER_JF_OFFSET)
    public fun setJf(value: Byte): Unit {
        segment.set(ValueLayout.JAVA_BYTE, Layouts.SOCK_FILTER_JF_OFFSET, value)
    }

    public fun getK(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.SOCK_FILTER_K_OFFSET)
    public fun setK(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.SOCK_FILTER_K_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): SockFilterSegment =
            SockFilterSegment(arena.allocate(Layouts.SOCK_FILTER))

        context(arena: Arena)
        public fun allocateArray(size: Int): MemorySegment =
            arena.allocate(MemoryLayout.sequenceLayout(size.toLong(), Layouts.SOCK_FILTER))
    }
}

/**
 * Type-safe wrapper for `struct sock_fprog`.
 */
@JvmInline
public value class SockFprogSegment(public val segment: MemorySegment) {
    public fun getLen(): Short = segment.get(ValueLayout.JAVA_SHORT, Layouts.SOCK_FPROG_LEN_OFFSET)
    public fun setLen(value: Short): Unit {
        segment.set(ValueLayout.JAVA_SHORT, Layouts.SOCK_FPROG_LEN_OFFSET, value)
    }

    public fun getFilter(): MemorySegment = segment.get(ValueLayout.ADDRESS, Layouts.SOCK_FPROG_FILTER_OFFSET).reinterpret(getLen().toLong() * Layouts.SOCK_FILTER_SIZE)
    public fun setFilter(value: MemorySegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.SOCK_FPROG_FILTER_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): SockFprogSegment =
            SockFprogSegment(arena.allocate(Layouts.SOCK_FPROG))
    }
}

/**
 * Type-safe wrapper for `struct landlock_ruleset_attr`.
 */
@JvmInline
public value class LandlockRulesetAttrSegment(public val segment: MemorySegment) {
    public val managed: ManagedSegment get() = ConfinedSegment(segment)

    public fun getHandledAccessFs(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_FS_OFFSET)
    public fun setHandledAccessFs(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_FS_OFFSET, value)
    }

    public fun getHandledAccessNet(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET)
    public fun setHandledAccessNet(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): LandlockRulesetAttrSegment =
            LandlockRulesetAttrSegment(arena.allocate(Layouts.LANDLOCK_RULESET_ATTR))

        context(arena: NativeArena)
        public fun allocate(): LandlockRulesetAttrSegment =
            LandlockRulesetAttrSegment(arena.arena.allocate(Layouts.LANDLOCK_RULESET_ATTR))
    }
}

/**
 * Type-safe wrapper for `struct landlock_path_beneath_attr`.
 */
@JvmInline
public value class LandlockPathBeneathAttrSegment(public val segment: MemorySegment) {
    public val managed: ManagedSegment get() = ConfinedSegment(segment)

    public fun getAllowedAccess(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_PATH_BENEATH_ATTR_ACCESS_OFFSET)
    public fun setAllowedAccess(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_PATH_BENEATH_ATTR_ACCESS_OFFSET, value)
    }

    public fun getParentFd(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.LANDLOCK_PATH_BENEATH_ATTR_FD_OFFSET)
    public fun setParentFd(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.LANDLOCK_PATH_BENEATH_ATTR_FD_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): LandlockPathBeneathAttrSegment =
            LandlockPathBeneathAttrSegment(arena.allocate(Layouts.LANDLOCK_PATH_BENEATH_ATTR))

        context(arena: NativeArena)
        public fun allocate(): LandlockPathBeneathAttrSegment =
            LandlockPathBeneathAttrSegment(arena.arena.allocate(Layouts.LANDLOCK_PATH_BENEATH_ATTR))
    }
}

/**
 * Type-safe wrapper for `struct pollfd`.
 */
@JvmInline
public value class PollFdSegment(public val segment: MemorySegment) {
    public fun getFd(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.POLLFD_FD_OFFSET)
    public fun setFd(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.POLLFD_FD_OFFSET, value)
    }

    public fun getEvents(): Short = segment.get(ValueLayout.JAVA_SHORT, Layouts.POLLFD_EVENTS_OFFSET)
    public fun setEvents(value: Short): Unit {
        segment.set(ValueLayout.JAVA_SHORT, Layouts.POLLFD_EVENTS_OFFSET, value)
    }

    public fun getRevents(): Short = segment.get(ValueLayout.JAVA_SHORT, Layouts.POLLFD_REVENTS_OFFSET)
    public fun setRevents(value: Short): Unit {
        segment.set(ValueLayout.JAVA_SHORT, Layouts.POLLFD_REVENTS_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): PollFdSegment =
            PollFdSegment(arena.allocate(Layouts.POLLFD))
    }
}

/**
 * Type-safe wrapper for errno capture state.
 */
@JvmInline
public value class ErrnoSegment(public val segment: MemorySegment) {
    public fun getErrno(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.ERRNO_OFFSET)

    public companion object {
        private val THREAD_LOCAL_SEGMENT = ThreadLocal.withInitial { Arena.global().allocate(Layouts.ERRNO) }

        /**
         * Returns a thread-local [ErrnoSegment] used to capture native call errors.
         * This avoids expensive [Arena.ofConfined] allocations in high-frequency loops.
         */
        public fun getThreadLocal(): ErrnoSegment = ErrnoSegment(THREAD_LOCAL_SEGMENT.get())

        context(arena: Arena)
        public fun allocate(): ErrnoSegment = ErrnoSegment(arena.allocate(Layouts.ERRNO))
    }
}

/**
 * Type-safe wrapper for `struct seccomp_notif_addfd`.
 */
@JvmInline
public value class SeccompNotifAddFdSegment(public val segment: MemorySegment) {
    public fun getId(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.SECCOMP_NOTIF_ADDFD_ID_OFFSET)
    public fun setId(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.SECCOMP_NOTIF_ADDFD_ID_OFFSET, value)
    }

    public fun getFlags(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_FLAGS_OFFSET)
    public fun setFlags(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_FLAGS_OFFSET, value)
    }

    public fun getSrcfd(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_SRCFD_OFFSET)
    public fun setSrcfd(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_SRCFD_OFFSET, value)
    }

    public fun getNewfd(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_NEWFD_OFFSET)
    public fun setNewfd(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_NEWFD_OFFSET, value)
    }

    public fun getNewfdFlags(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_NEWFD_FLAGS_OFFSET)
    public fun setNewfdFlags(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.SECCOMP_NOTIF_ADDFD_NEWFD_FLAGS_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): SeccompNotifAddFdSegment =
            SeccompNotifAddFdSegment(arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD))
    }
}

/**
 * Type-safe wrapper for `struct iovec`.
 */
@JvmInline
public value class IovecSegment(public val segment: MemorySegment) {
    public fun getIovBase(): MemorySegment = segment.get(ValueLayout.ADDRESS, Layouts.IOVEC_BASE_OFFSET)
    public fun setIovBase(value: MemorySegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.IOVEC_BASE_OFFSET, value)
    }

    public fun getIovLen(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.IOVEC_LEN_OFFSET)
    public fun setIovLen(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.IOVEC_LEN_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): IovecSegment =
            IovecSegment(arena.allocate(Layouts.IOVEC))
    }
}

/**
 * Type-safe wrapper for `struct msghdr`.
 */
@JvmInline
public value class MsghdrSegment(public val segment: MemorySegment) {
    public fun getMsgName(): MemorySegment = segment.get(ValueLayout.ADDRESS, Layouts.MSGHDR_NAME_OFFSET)
    public fun setMsgName(value: MemorySegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.MSGHDR_NAME_OFFSET, value)
    }

    public fun getMsgNamelen(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.MSGHDR_NAMELEN_OFFSET)
    public fun setMsgNamelen(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.MSGHDR_NAMELEN_OFFSET, value)
    }

    public fun getMsgIov(): MemorySegment = segment.get(ValueLayout.ADDRESS, Layouts.MSGHDR_IOV_OFFSET)
    public fun setMsgIov(value: MemorySegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.MSGHDR_IOV_OFFSET, value)
    }

    public fun getMsgIovlen(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.MSGHDR_IOVLEN_OFFSET)
    public fun setMsgIovlen(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.MSGHDR_IOVLEN_OFFSET, value)
    }

    public fun getMsgControl(): MemorySegment = segment.get(ValueLayout.ADDRESS, Layouts.MSGHDR_CONTROL_OFFSET)
    public fun setMsgControl(value: MemorySegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.MSGHDR_CONTROL_OFFSET, value)
    }

    public fun getMsgControllen(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.MSGHDR_CONTROLLEN_OFFSET)
    public fun setMsgControllen(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.MSGHDR_CONTROLLEN_OFFSET, value)
    }

    public fun getMsgFlags(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.MSGHDR_FLAGS_OFFSET)
    public fun setMsgFlags(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.MSGHDR_FLAGS_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): MsghdrSegment =
            MsghdrSegment(arena.allocate(Layouts.MSGHDR))
    }
}

/**
 * Type-safe wrapper for `struct cmsghdr`.
 */
@JvmInline
public value class CmsghdrSegment(public val segment: MemorySegment) {
    public fun getCmsgLen(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.CMSGHDR_LEN_OFFSET)
    public fun setCmsgLen(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.CMSGHDR_LEN_OFFSET, value)
    }

    public fun getCmsgLevel(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.CMSGHDR_LEVEL_OFFSET)
    public fun setCmsgLevel(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.CMSGHDR_LEVEL_OFFSET, value)
    }

    public fun getCmsgType(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.CMSGHDR_TYPE_OFFSET)
    public fun setCmsgType(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.CMSGHDR_TYPE_OFFSET, value)
    }

    public fun getDataFd(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.CMSGHDR_DATA_OFFSET)
    public fun setDataFd(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.CMSGHDR_DATA_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): CmsghdrSegment =
            CmsghdrSegment(arena.allocate(Layouts.CMSGHDR))
    }
}

/**
 * Type-safe wrapper for `struct sockaddr_un`.
 */
@JvmInline
public value class SockaddrUnSegment(public val segment: MemorySegment) {
    public fun getSunFamily(): Short = segment.get(ValueLayout.JAVA_SHORT, Layouts.SOCKADDR_UN_FAMILY_OFFSET)
    public fun setSunFamily(value: Short): Unit {
        segment.set(ValueLayout.JAVA_SHORT, Layouts.SOCKADDR_UN_FAMILY_OFFSET, value)
    }

    public fun getSunPath(): MemorySegment = segment.asSlice(Layouts.SOCKADDR_UN_PATH_OFFSET, Layouts.SOCKADDR_UN_PATH_SIZE)

    public companion object {
        context(arena: Arena)
        public fun allocate(): SockaddrUnSegment =
            SockaddrUnSegment(arena.allocate(Layouts.SOCKADDR_UN))
    }
}

/**
 * Type-safe wrapper for supervisor response packet (13 bytes).
 */
@JvmInline
public value class SupervisorResponseSegment(public val segment: MemorySegment) {
    public fun getId(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.SUPERVISOR_RESPONSE_ID_OFFSET)
    public fun setId(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.SUPERVISOR_RESPONSE_ID_OFFSET, value)
    }

    public fun getDecision(): Byte = segment.get(ValueLayout.JAVA_BYTE, Layouts.SUPERVISOR_RESPONSE_DECISION_OFFSET)
    public fun setDecision(value: Byte): Unit {
        segment.set(ValueLayout.JAVA_BYTE, Layouts.SUPERVISOR_RESPONSE_DECISION_OFFSET, value)
    }

    public fun getErrorNr(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.SUPERVISOR_RESPONSE_ERROR_OFFSET)
    public fun setErrorNr(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.SUPERVISOR_RESPONSE_ERROR_OFFSET, value)
    }

    public companion object {
        context(arena: Arena)
        public fun allocate(): SupervisorResponseSegment =
            SupervisorResponseSegment(arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE))
    }
}

public fun MemorySegment.readByte(offset: Long): Byte = this.get(ValueLayout.JAVA_BYTE, offset)
public fun MemorySegment.writeByte(offset: Long, value: Byte) { this.set(ValueLayout.JAVA_BYTE, offset, value) }

public fun MemorySegment.readShort(offset: Long): Short = this.get(ValueLayout.JAVA_SHORT, offset)
public fun MemorySegment.writeShort(offset: Long, value: Short) { this.set(ValueLayout.JAVA_SHORT, offset, value) }

public fun MemorySegment.readInt(offset: Long): Int = this.get(ValueLayout.JAVA_INT, offset)
public fun MemorySegment.writeInt(offset: Long, value: Int) { this.set(ValueLayout.JAVA_INT, offset, value) }

public fun MemorySegment.readIntUnaligned(offset: Long): Int = this.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
public fun MemorySegment.writeIntUnaligned(offset: Long, value: Int) { this.set(ValueLayout.JAVA_INT_UNALIGNED, offset, value) }

public fun MemorySegment.readLong(offset: Long): Long = this.get(ValueLayout.JAVA_LONG, offset)
public fun MemorySegment.writeLong(offset: Long, value: Long) { this.set(ValueLayout.JAVA_LONG, offset, value) }

public fun MemorySegment.readLongUnaligned(offset: Long): Long = this.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)
public fun MemorySegment.writeLongUnaligned(offset: Long, value: Long) { this.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, value) }

public fun MemorySegment.readAddress(offset: Long): MemorySegment = this.get(ValueLayout.ADDRESS, offset)
public fun MemorySegment.writeAddress(offset: Long, value: MemorySegment) { this.set(ValueLayout.ADDRESS, offset, value) }

private val JAVA_INT_BE = ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN)
private val JAVA_INT_BE_UNALIGNED = ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN).withByteAlignment(1)
private val JAVA_LONG_BE = ValueLayout.JAVA_LONG.withOrder(java.nio.ByteOrder.BIG_ENDIAN)
private val JAVA_LONG_BE_UNALIGNED = ValueLayout.JAVA_LONG.withOrder(java.nio.ByteOrder.BIG_ENDIAN).withByteAlignment(1)

public fun MemorySegment.writeIntBigEndian(offset: Long, value: Int) {
    this.set(JAVA_INT_BE, offset, value)
}

public fun MemorySegment.writeIntBigEndianUnaligned(offset: Long, value: Int) {
    this.set(JAVA_INT_BE_UNALIGNED, offset, value)
}

public fun MemorySegment.writeLongBigEndian(offset: Long, value: Long) {
    this.set(JAVA_LONG_BE, offset, value)
}

public fun MemorySegment.writeLongBigEndianUnaligned(offset: Long, value: Long) {
    this.set(JAVA_LONG_BE_UNALIGNED, offset, value)
}
public fun getSystemStrerror(errno: Int): String? {
    val linker = java.lang.foreign.Linker.nativeLinker()
    val stdlib = linker.defaultLookup()
    val strerrorAddress = stdlib.find("strerror").orElse(null) ?: return null
    val strerror = linker.downcallHandle(
        strerrorAddress,
        java.lang.foreign.FunctionDescriptor.of(
            java.lang.foreign.ValueLayout.ADDRESS,
            java.lang.foreign.ValueLayout.JAVA_INT
        )
    )
    val segment = strerror.invoke(errno) as java.lang.foreign.MemorySegment
    return segment.reinterpret(1024).getString(0)
}
