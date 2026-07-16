package io.mazewall.ffi.memory

import io.mazewall.ffi.Layouts
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Type-safe wrapper for `struct sock_filter`.
 */
@JvmInline
public value class SockFilterSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

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
        context(arena: NativeArena)
        public fun allocate(): SockFilterSegment =
            SockFilterSegment(ConfinedSegment(arena.arena.allocate(Layouts.SOCK_FILTER)))

        context(arena: NativeArena)
        public fun allocateArray(size: Int): ManagedSegment =
            ConfinedSegment(arena.arena.allocate(MemoryLayout.sequenceLayout(size.toLong(), Layouts.SOCK_FILTER)))
    }
}

/**
 * Type-safe wrapper for `struct sock_fprog`.
 */
@JvmInline
public value class SockFprogSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

    public fun getLen(): Short = segment.get(ValueLayout.JAVA_SHORT, Layouts.SOCK_FPROG_LEN_OFFSET)
    public fun setLen(value: Short): Unit {
        segment.set(ValueLayout.JAVA_SHORT, Layouts.SOCK_FPROG_LEN_OFFSET, value)
    }

    public fun getFilter(): ManagedSegment = ConfinedSegment(segment.get(ValueLayout.ADDRESS, Layouts.SOCK_FPROG_FILTER_OFFSET).reinterpret(getLen().toLong() * Layouts.SOCK_FILTER_SIZE))
    public fun setFilter(value: ManagedSegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.SOCK_FPROG_FILTER_OFFSET, value.native)
    }

    public companion object {
        context(arena: NativeArena)
        public fun allocate(): SockFprogSegment =
            SockFprogSegment(ConfinedSegment(arena.arena.allocate(Layouts.SOCK_FPROG)))
    }
}

/**
 * Type-safe wrapper for `struct landlock_ruleset_attr`.
 */
@JvmInline
public value class LandlockRulesetAttrSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

    public fun getHandledAccessFs(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_FS_OFFSET)
    public fun setHandledAccessFs(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_FS_OFFSET, value)
    }

    public fun getHandledAccessNet(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET)
    public fun setHandledAccessNet(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET, value)
    }

    public companion object {
        context(arena: NativeArena)
        public fun allocate(): LandlockRulesetAttrSegment =
            LandlockRulesetAttrSegment(ConfinedSegment(arena.arena.allocate(Layouts.LANDLOCK_RULESET_ATTR)))
    }
}

/**
 * Type-safe wrapper for `struct landlock_path_beneath_attr`.
 */
@JvmInline
public value class LandlockPathBeneathAttrSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

    public fun getAllowedAccess(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_PATH_BENEATH_ATTR_ACCESS_OFFSET)
    public fun setAllowedAccess(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_PATH_BENEATH_ATTR_ACCESS_OFFSET, value)
    }

    public fun getParentFd(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.LANDLOCK_PATH_BENEATH_ATTR_FD_OFFSET)
    public fun setParentFd(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.LANDLOCK_PATH_BENEATH_ATTR_FD_OFFSET, value)
    }

    public companion object {
        context(arena: NativeArena)
        public fun allocate(): LandlockPathBeneathAttrSegment =
            LandlockPathBeneathAttrSegment(ConfinedSegment(arena.arena.allocate(Layouts.LANDLOCK_PATH_BENEATH_ATTR)))
    }
}

/**
 * Type-safe wrapper for `struct pollfd`.
 */
@JvmInline
public value class PollFdSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

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
        context(arena: NativeArena)
        public fun allocate(): PollFdSegment =
            PollFdSegment(ConfinedSegment(arena.arena.allocate(Layouts.POLLFD)))

        context(arena: NativeArena)
        public fun allocateArray(size: Int): ManagedSegment =
            ConfinedSegment(arena.arena.allocate(MemoryLayout.sequenceLayout(size.toLong(), Layouts.POLLFD)))
    }
}

/**
 * Type-safe wrapper for errno capture state.
 */
@JvmInline
public value class ErrnoSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

    public fun getErrno(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.ERRNO_OFFSET)

    public companion object {
        private val THREAD_LOCAL_SEGMENT = ThreadLocal.withInitial { java.lang.foreign.Arena.global().allocate(Layouts.ERRNO) }

        /**
         * Returns a thread-local [ErrnoSegment] used to capture native call errors.
         * This avoids expensive [Arena.ofConfined] allocations in high-frequency loops.
         */
        public fun getThreadLocal(): ErrnoSegment = ErrnoSegment(SharedSegment(THREAD_LOCAL_SEGMENT.get()))

        context(arena: NativeArena)
        public fun allocate(): ErrnoSegment = ErrnoSegment(ConfinedSegment(arena.arena.allocate(Layouts.ERRNO)))
    }
}

/**
 * Type-safe wrapper for `struct seccomp_notif_addfd`.
 */
@JvmInline
public value class SeccompNotifAddFdSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

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
        context(arena: NativeArena)
        public fun allocate(): SeccompNotifAddFdSegment =
            SeccompNotifAddFdSegment(ConfinedSegment(arena.arena.allocate(Layouts.SECCOMP_NOTIF_ADDFD)))
    }
}

/**
 * Type-safe wrapper for `struct iovec`.
 */
@JvmInline
public value class IovecSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

    public fun getIovBase(): ManagedSegment = ConfinedSegment(segment.get(ValueLayout.ADDRESS, Layouts.IOVEC_BASE_OFFSET))
    public fun setIovBase(value: ManagedSegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.IOVEC_BASE_OFFSET, value.native)
    }

    public fun getIovLen(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.IOVEC_LEN_OFFSET)
    public fun setIovLen(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.IOVEC_LEN_OFFSET, value)
    }

    public companion object {
        context(arena: NativeArena)
        public fun allocate(): IovecSegment =
            IovecSegment(ConfinedSegment(arena.arena.allocate(Layouts.IOVEC)))
    }
}

/**
 * Type-safe wrapper for `struct msghdr`.
 */
@JvmInline
public value class MsghdrSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

    public fun getMsgName(): ManagedSegment = ConfinedSegment(segment.get(ValueLayout.ADDRESS, Layouts.MSGHDR_NAME_OFFSET))
    public fun setMsgName(value: ManagedSegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.MSGHDR_NAME_OFFSET, value.native)
    }

    public fun getMsgNamelen(): Int = segment.get(ValueLayout.JAVA_INT, Layouts.MSGHDR_NAMELEN_OFFSET)
    public fun setMsgNamelen(value: Int): Unit {
        segment.set(ValueLayout.JAVA_INT, Layouts.MSGHDR_NAMELEN_OFFSET, value)
    }

    public fun getMsgIov(): ManagedSegment = ConfinedSegment(segment.get(ValueLayout.ADDRESS, Layouts.MSGHDR_IOV_OFFSET))
    public fun setMsgIov(value: ManagedSegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.MSGHDR_IOV_OFFSET, value.native)
    }

    public fun getMsgIovlen(): Long = segment.get(ValueLayout.JAVA_LONG, Layouts.MSGHDR_IOVLEN_OFFSET)
    public fun setMsgIovlen(value: Long): Unit {
        segment.set(ValueLayout.JAVA_LONG, Layouts.MSGHDR_IOVLEN_OFFSET, value)
    }

    public fun getMsgControl(): ManagedSegment = ConfinedSegment(segment.get(ValueLayout.ADDRESS, Layouts.MSGHDR_CONTROL_OFFSET))
    public fun setMsgControl(value: ManagedSegment): Unit {
        segment.set(ValueLayout.ADDRESS, Layouts.MSGHDR_CONTROL_OFFSET, value.native)
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
        context(arena: NativeArena)
        public fun allocate(): MsghdrSegment =
            MsghdrSegment(ConfinedSegment(arena.arena.allocate(Layouts.MSGHDR)))
    }
}

/**
 * Type-safe wrapper for `struct cmsghdr`.
 */
@JvmInline
public value class CmsghdrSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

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
        context(arena: NativeArena)
        public fun allocate(): CmsghdrSegment =
            CmsghdrSegment(ConfinedSegment(arena.arena.allocate(Layouts.CMSGHDR)))
    }
}

/**
 * Type-safe wrapper for `struct sockaddr_un`.
 */
@JvmInline
public value class SockaddrUnSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

    public fun getSunFamily(): Short = segment.get(ValueLayout.JAVA_SHORT, Layouts.SOCKADDR_UN_FAMILY_OFFSET)
    public fun setSunFamily(value: Short): Unit {
        segment.set(ValueLayout.JAVA_SHORT, Layouts.SOCKADDR_UN_FAMILY_OFFSET, value)
    }

    public fun getSunPath(): ManagedSegment = ConfinedSegment(segment.asSlice(Layouts.SOCKADDR_UN_PATH_OFFSET, Layouts.SOCKADDR_UN_PATH_SIZE))

    public companion object {
        context(arena: NativeArena)
        public fun allocate(): SockaddrUnSegment =
            SockaddrUnSegment(ConfinedSegment(arena.arena.allocate(Layouts.SOCKADDR_UN)))
    }
}

/**
 * Type-safe wrapper for supervisor response packet (13 bytes).
 */
@JvmInline
public value class SupervisorResponseSegment(public val managed: ManagedSegment) {
    public val segment: MemorySegment get() = managed.native

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
        context(arena: NativeArena)
        public fun allocate(): SupervisorResponseSegment =
            SupervisorResponseSegment(ConfinedSegment(arena.arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)))
    }
}

public fun ManagedSegment.readByte(offset: Long): Byte = this.native.get(ValueLayout.JAVA_BYTE, offset)
public fun ManagedSegment.writeByte(offset: Long, value: Byte) { this.native.set(ValueLayout.JAVA_BYTE, offset, value) }

public fun ManagedSegment.readShort(offset: Long): Short = this.native.get(ValueLayout.JAVA_SHORT, offset)
public fun ManagedSegment.writeShort(offset: Long, value: Short) { this.native.set(ValueLayout.JAVA_SHORT, offset, value) }

public fun ManagedSegment.readInt(offset: Long): Int = this.native.get(ValueLayout.JAVA_INT, offset)
public fun ManagedSegment.writeInt(offset: Long, value: Int) { this.native.set(ValueLayout.JAVA_INT, offset, value) }

public fun ManagedSegment.readIntUnaligned(offset: Long): Int = this.native.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
public fun ManagedSegment.writeIntUnaligned(offset: Long, value: Int) { this.native.set(ValueLayout.JAVA_INT_UNALIGNED, offset, value) }

public fun ManagedSegment.readLong(offset: Long): Long = this.native.get(ValueLayout.JAVA_LONG, offset)
public fun ManagedSegment.writeLong(offset: Long, value: Long) { this.native.set(ValueLayout.JAVA_LONG, offset, value) }

public fun ManagedSegment.readLongUnaligned(offset: Long): Long = this.native.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)
public fun ManagedSegment.writeLongUnaligned(offset: Long, value: Long) { this.native.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, value) }

public fun ManagedSegment.readAddress(offset: Long): ManagedSegment = ConfinedSegment(this.native.get(ValueLayout.ADDRESS, offset))
public fun ManagedSegment.writeAddress(offset: Long, value: ManagedSegment) { this.native.set(ValueLayout.ADDRESS, offset, value.native) }

private val JAVA_INT_BE = ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN)
private val JAVA_INT_BE_UNALIGNED = ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN).withByteAlignment(1)
private val JAVA_LONG_BE = ValueLayout.JAVA_LONG.withOrder(java.nio.ByteOrder.BIG_ENDIAN)
private val JAVA_LONG_BE_UNALIGNED = ValueLayout.JAVA_LONG.withOrder(java.nio.ByteOrder.BIG_ENDIAN).withByteAlignment(1)

public fun ManagedSegment.writeIntBigEndian(offset: Long, value: Int) {
    this.native.set(JAVA_INT_BE, offset, value)
}

public fun ManagedSegment.writeIntBigEndianUnaligned(offset: Long, value: Int) {
    this.native.set(JAVA_INT_BE_UNALIGNED, offset, value)
}

public fun ManagedSegment.writeLongBigEndian(offset: Long, value: Long) {
    this.native.set(JAVA_LONG_BE, offset, value)
}

public fun ManagedSegment.writeLongBigEndianUnaligned(offset: Long, value: Long) {
    this.native.set(JAVA_LONG_BE_UNALIGNED, offset, value)
}

internal object StrerrorHelper {
    private val linker = java.lang.foreign.Linker.nativeLinker()
    private val stdlib = linker.defaultLookup()
    private val strerrorAddress = stdlib.find("strerror").orElse(null)
    private val strerrorHandle = if (strerrorAddress != null) linker.downcallHandle(
        strerrorAddress,
        java.lang.foreign.FunctionDescriptor.of(
            java.lang.foreign.ValueLayout.ADDRESS,
            java.lang.foreign.ValueLayout.JAVA_INT
        )
    ) else null

    fun getSystemStrerror(errno: Int): String? {
        if (strerrorHandle == null) return null
        val segment = strerrorHandle.invoke(errno) as java.lang.foreign.MemorySegment
        return segment.reinterpret(1024).getString(0)
    }
}

public fun getSystemStrerror(errno: Int): String? = StrerrorHelper.getSystemStrerror(errno)
