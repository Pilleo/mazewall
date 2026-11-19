package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@EnabledIfLinuxAndSupported
class HotSpotObservedTargetIdentityTest {
    @Test
    fun `unified membership uses raw absolute cgroup paths`() {
        assertEquals(Path.of("/"), observedCgroupPath("0::/\n"))
        assertEquals(Path.of("/user.slice/test.scope"), observedCgroupPath("1:cpu:/legacy\n0::/user.slice/test.scope\n"))
        for (invalid in listOf("", "0::relative", "0::/../escape", "0::/a/../b", "0::/a\n0::/b", "1:cpu:/legacy", "0::/a/./b", "0:://a", "0::/a/", "0::/a\u0000b")) {
            assertFailsWith<IllegalArgumentException>(invalid) { observedCgroupPath(invalid) }
        }
    }

    @Test
    fun `mount namespace identity is the exact kernel inode without time conversion`() {
        assertEquals(4026531841uL, observedMountNamespace("mnt:[4026531841]"))
        for (invalid in listOf("", "mnt:[0]", "pid:[4026531841]", "mnt:[-1]", "mnt:[1]suffix", "mnt:[18446744073709551616]")) {
            assertFailsWith<IllegalArgumentException>(invalid) { observedMountNamespace(invalid) }
        }
    }
}
