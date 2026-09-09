package io.mazewall.enforcer.supervisor

import io.mazewall.core.OpenFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpenRouteExecutorTest {
    @ParameterizedTest
    @ValueSource(ints = [51005, 51006])
    fun `a failed intermediate close cannot leak a successfully acquired successor`(failedFd: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.fileSystem.onClose = { fd ->
            fixture.closed += fd.value
            if (fd.value == failedFd) SupervisorBoundaryFixture.failure(13) else SupervisorBoundaryFixture.success(0)
        }
        NativeArena.ofConfined().use { arena ->
            assertThrows(IllegalStateException::class.java) {
                with(arena) { executor(fixture).execute(SupervisedOpen.OpenAt(TraceeDirFd(5), "secret", OpenFlags(0)), Tid(77)) }
            }
        }
        val expected = if (failedFd == 51005) listOf(51005, 51006) else listOf(51005, 51006, 51002)
        assertEquals(expected, fixture.closed)
    }

    @Test
    fun `open variants preserve creation flags mode resolve and CLOEXEC`() {
        val cases = listOf(
            SupervisedOpen.Open("/tmp/new", OpenFlags(524353), 416) to 0L,
            SupervisedOpen.OpenAt(TraceeDirFd(5), "/tmp/new", OpenFlags(524353), 416) to 0L,
            SupervisedOpen.OpenAt2(TraceeDirFd(5), "/tmp/new", OpenHow(OpenFlags(524353), 416, 1)) to 9L,
        )
        for ((request, resolve) in cases) {
            val fixture = SupervisorBoundaryFixture()
            fixture.engine.fileSystem.onOpenat2 = { dirfd, _, how, size ->
                assertEquals(-100, dirfd.value)
                assertEquals(24L, size)
                assertEquals(524353L, how.readLong(0))
                assertEquals(416L, how.readLong(8))
                assertEquals(resolve, how.readLong(16))
                SupervisorBoundaryFixture.success(51002)
            }
            NativeArena.ofConfined().use { arena ->
                val effect = with(arena) { executor(fixture).execute(request, Tid(77)) }
                val add = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, effect)
                assertEquals(524288, add.flags.value)
                assertEquals(0, fixture.replies.size)
                assertEquals(emptyList<Int>(), fixture.closed)
                add.descriptor.close()
                assertEquals(listOf(51002), fixture.closed)
            }
        }
    }

    @Test
    fun `relative openat imports the tracee descriptor and releases only owned locals`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.process.onPidfdOpen = { pid, flags ->
            assertEquals(77, pid)
            assertEquals(0, flags)
            SupervisorBoundaryFixture.success(51005)
        }
        fixture.engine.process.onPidfdGetFd = { pidfd, target, _ ->
            assertEquals(51005, pidfd.value)
            assertEquals(5, target)
            SupervisorBoundaryFixture.success(51006)
        }
        fixture.engine.fileSystem.onOpenat2 = { fd, _, _, _ ->
            assertEquals(51006, fd.value)
            SupervisorBoundaryFixture.success(51002)
        }
        NativeArena.ofConfined().use { arena ->
            val request = SupervisedOpen.OpenAt(TraceeDirFd(5), "relative", OpenFlags(0))
            val effect = with(arena) { executor(fixture).execute(request, Tid(77)) } as SupervisorReplyEffect.AddFdAndReply
            assertEquals(listOf(51005, 51006), fixture.closed)
            effect.descriptor.close()
            assertEquals(listOf(51005, 51006, 51002), fixture.closed)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 13, 9])
    fun `failed pidfd import retains errno and cannot open a fallback path`(errno: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.process.onPidfdGetFd = { _, _, _ -> SupervisorBoundaryFixture.failure(errno) }
        fixture.engine.fileSystem.onOpenat2 = { _, _, _, _ -> error("must not fall back to another directory") }
        NativeArena.ofConfined().use { arena ->
            val effect = with(arena) { executor(fixture).execute(SupervisedOpen.OpenAt(TraceeDirFd(5), "secret", OpenFlags(0)), Tid(77)) }
            assertEquals(SupervisorReplyEffect.Error(errno), effect)
            assertEquals(listOf(51005), fixture.closed)
            assertEquals(0, fixture.replies.size)
        }
    }

    private fun executor(fixture: SupervisorBoundaryFixture) = OpenRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it })
}
