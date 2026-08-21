package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.enforcer.supervisor.SupervisorSessionHandler
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupervisorExecPathInspectionTest {
    @Test
    fun `extractNotificationArgs inspects execve path when tracee memory is readable`() {
        val pathBytes = "/bin/true\u0000".toByteArray()
        val mockMemory = object : MockNativeMemory() {
            override fun processVmReadv(
                pid: Pid,
                localIov: ManagedSegment,
                liovcnt: Long,
                remoteIov: ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val localBase = localIov.readLong(0)
                val localLen = localIov.readLong(8)
                val localBuf = MemorySegment.ofAddress(localBase).reinterpret(localLen)
                for (i in pathBytes.indices) {
                    localBuf.set(ValueLayout.JAVA_BYTE, i.toLong(), pathBytes[i])
                }
                return LinuxNative.SyscallResult.Success(pathBytes.size.toLong())
            }
        }
        val mockEngine = MockNativeEngine(memory = mockMemory)
        try {
            LinuxNative.setEngine(mockEngine)
            val handler = SupervisorSessionHandler(
                FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
                FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11)
            )
            val method = SupervisorSessionHandler::class.java.declaredMethods.first {
                it.name.startsWith("extractNotificationArgs") && !it.name.contains("$") && it.parameterCount >= 4
            }
            method.isAccessible = true
            val arch = io.mazewall.core.Arch.current()
            NativeArena.ofConfined().use { arena ->
                val args = LongArray(6).apply { this[0] = 0x1000L }
                val paramTypes = method.parameterTypes
                val argsToPass = arrayOfNulls<Any>(paramTypes.size)
                for (i in paramTypes.indices) {
                    val type = paramTypes[i]
                    when {
                        type.name.contains("NativeArena") || type.name.contains("Arena") -> argsToPass[i] = arena
                        type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java -> argsToPass[i] = arch.execve
                        type.name.contains("Tid") -> argsToPass[i] = Tid(999)
                        type == LongArray::class.java -> argsToPass[i] = args
                        type.name.contains("Arch") -> argsToPass[i] = arch
                    }
                }
                val result = method.invoke(handler, *argsToPass)
                val pathField = result.javaClass.getDeclaredField("pathStr")
                pathField.isAccessible = true
                assertEquals("/bin/true", pathField.get(result))
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
