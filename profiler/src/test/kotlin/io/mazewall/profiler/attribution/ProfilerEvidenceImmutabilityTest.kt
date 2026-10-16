package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfilerEvidenceImmutabilityTest {
    @Test
    fun `dictionary owns its frame key and returned definition`() {
        val frames = mutableListOf(frame("one"))
        val dictionary = StackTraceDictionary()
        val first = dictionary.intern(frames, StackCaptureQuality.COMPLETE)

        frames += frame("two")

        assertEquals(listOf(frame("one")), first.frames)
        assertFailsWith<UnsupportedOperationException> {
            (first.frames as MutableList).add(frame("three"))
        }
        assertEquals(first, dictionary.intern(listOf(frame("one")), StackCaptureQuality.COMPLETE))
    }

    @Test
    fun `syscall attribution and tier profile own their lists`() {
        val args = MutableList(6) { it.toLong() }
        val attribution = SyscallAttribution(
            TaskIdentity(1, 2, 3),
            4,
            257,
            args,
            InvocationId(5),
            AttributionStatus.PENDING_DICTIONARY,
        )
        val records = mutableListOf(ResolvedSyscall(attribution, null, ResolutionStatus.DICTIONARY_UNRESOLVED))
        val profile = TierEProfile(records, SessionIntegrity())

        args[0] = 99
        records.clear()

        assertEquals(
            0,
            profile.syscalls
            .single()
            .observation.args[0],
        )
        assertFailsWith<UnsupportedOperationException> { (profile.syscalls as MutableList).clear() }
    }

    @Test
    fun `owned syscall argument transfer preserves an immutable list view`() {
        val ownedArgs = LongArray(6) { it.toLong() }
        val attribution = SyscallAttribution.fromOwnedArguments(
            task = TaskIdentity(1, 2, 3),
            taskSequence = 4,
            syscallNumber = 257,
            ownedArgs = ownedArgs,
            invocationId = InvocationId(5),
            attributionStatus = AttributionStatus.PENDING_DICTIONARY,
        )

        assertEquals((0L..5L).toList(), attribution.args)
        assertFailsWith<UnsupportedOperationException> { (attribution.args as MutableList).clear() }
    }

    private fun frame(method: String) = ManagedFrame("loader", "pkg.Type", method, "()V", 1, ManagedFrameKind.JAVA)
}
