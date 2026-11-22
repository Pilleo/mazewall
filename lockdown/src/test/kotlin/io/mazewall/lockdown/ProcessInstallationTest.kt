package io.mazewall.lockdown

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ProcessInstallationTest {
    @Test
    fun `validates filterDepth and architecture`() {
        val installation = ProcessInstallation.create(filterDepth = 1, architecture = "amd64")
        assertEquals(1, installation.filterDepth)
        assertEquals("amd64", installation.architecture)
        assertEquals("ProcessInstallation(filterDepth=1, architecture=amd64)", installation.toString())

        assertFailsWith<IllegalArgumentException> {
            ProcessInstallation.create(filterDepth = 0, architecture = "amd64")
        }
        assertFailsWith<IllegalArgumentException> {
            ProcessInstallation.create(filterDepth = 1, architecture = " ")
        }
    }

    @Test
    fun `implements value equality and hashCode`() {
        val first = ProcessInstallation.create(filterDepth = 2, architecture = "aarch64")
        val second = ProcessInstallation.create(filterDepth = 2, architecture = "aarch64")
        val other = ProcessInstallation.create(filterDepth = 1, architecture = "amd64")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, other)
    }
}
