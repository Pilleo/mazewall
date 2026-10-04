package io.mazewall.profiler.hotspot

import io.mazewall.profiler.api.EbpfLoad
import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfileOptions
import io.mazewall.profiler.api.ProfileStrategy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HotSpotStrategyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `explicit HotSpot does not depend on parent eBPF authority and AUTO remains USER_NOTIF`() {
        for (load in listOf(EbpfLoad.Available, EbpfLoad.UserNamespaceRoot, EbpfLoad.Denied("ordinary parent"))) {
            assertEquals(ProfileStrategy.HOTSPOT_AGENTLESS, MazewallProfiler.resolve(ProfileStrategy.HOTSPOT_AGENTLESS, load).first)
            assertEquals(ProfileStrategy.USER_NOTIF, MazewallProfiler.resolve(ProfileStrategy.AUTO, load).first)
        }
    }

    @Test
    fun `unsupported scope and mixed recorded log refuse before launch`() {
        for (options in listOf(
            ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS),
            ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true, captureStacks = false),
            ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true, ebpfEventLog = directory.resolve("recorded.log")),
        )) {
            assertFailsWith<IllegalArgumentException> { MazewallProfiler.open(options) }
        }
    }

    @Test
    fun `missing verified assets prevents user block and has no USER_NOTIF fallback`() {
        val ran = AtomicBoolean()
        MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true)).use { profiler ->
            assertFailsWith<HotSpotProfilingException> { profiler.profile(HotSpotOptions(directory)) { ran.set(true) } }
        }
        assertFalse(ran.get())
    }
}
