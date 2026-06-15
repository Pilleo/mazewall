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
    }
}

/**
 * Type-safe wrapper for `struct landlock_path_beneath_attr`.
 */
@JvmInline
public value class LandlockPathBeneathAttrSegment(public val segment: MemorySegment) {
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
        context(arena: Arena)
        public fun allocate(): ErrnoSegment = ErrnoSegment(arena.allocate(Layouts.ERRNO))
    }
}
