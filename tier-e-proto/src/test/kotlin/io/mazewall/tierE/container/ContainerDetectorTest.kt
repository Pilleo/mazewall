package io.mazewall.tierE.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class ContainerDetectorTest {

    private val cid = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b"

    @Test
    fun `docker cgroup v2 path detected`() {
        val info = ContainerDetector.detect(listOf("0::/docker/$cid"))
        assertNotNull(info)
        assertEquals("docker", info.runtime)
        assertEquals(cid, info.containerId)
    }

    @Test
    fun `containerd scope path detected`() {
        val info = ContainerDetector.detect(
            listOf("0::/system.slice/docker-abc123def456.scope"),
        )
        assertNotNull(info)
        assertEquals("docker", info.runtime)
        assertEquals("abc123def456", info.containerId)
    }

    @Test
    fun `kubepods pod path detected`() {
        val lines = listOf("0::/kubepods/burstable/pod$cid/$cid")
        val info = ContainerDetector.detect(lines)
        assertNotNull(info)
        assertEquals(cid, info.containerId)
    }

    @Test
    fun `podman libpod path detected`() {
        val info = ContainerDetector.detect(listOf("0::/libpod_$cid"))
        assertNotNull(info)
        assertEquals("podman", info.runtime)
    }

    @Test
    fun `host process returns null`() {
        assertNull(ContainerDetector.detect(listOf("0::/system.slice/sshd.service")))
        assertNull(ContainerDetector.detect(listOf("0::/init.scope")))
    }
}
