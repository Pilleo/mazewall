package io.mazewall.platform.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ForeignFdGuardTest {
    private lateinit var testContext: ExtensionContext

    @JvmField
    @RegisterExtension
    val contextCapture = BeforeEachCallback { testContext = it }

    @Test
    fun enumeratedOwnedDescriptorDisappearsBeforeFirstReadlink() =
        withOwnedFile { owner, entry, target ->
        var observedFailure: NoSuchFileException? = null
        val guard = ForeignFdGuard(ROOT) { path ->
            if (path == entry && owner.isOpen) {
                owner.close()
                observedFailure = assertThrows(NoSuchFileException::class.java) { Files.readSymbolicLink(path) }
            }
            Files.readSymbolicLink(path)
        }
        val context = context()
        guard.beforeEach(context)
        assertTrue(observedFailure != null, "Real acquired $entry -> $target must disappear at the hook")
        guard.afterEach(context)
    }

    @Test
    fun verifiedForeignDescriptorClosedBetweenSnapshotsIsDetected() =
        withOwnedFile { owner, entry, target ->
        val guard = ForeignFdGuard()
        val context = context()
        guard.beforeEach(context)
        owner.close()
        assertThrows(NoSuchFileException::class.java) { Files.readSymbolicLink(entry) }
        assertForeignClose(guard, context, entry, target)
    }

    @Test
    fun verifiedForeignDescriptorDisappearsAtAfterReadlinkIsDetected() =
        withOwnedFile { owner, entry, target ->
        var after = false
        var hookRan = false
        val guard = ForeignFdGuard(ROOT) { path ->
            if (after && path == entry) {
                hookRan = true
                owner.close()
                assertThrows(NoSuchFileException::class.java) { Files.readSymbolicLink(path) }
            }
            Files.readSymbolicLink(path)
        }
        val context = context()
        guard.beforeEach(context)
        after = true
        assertForeignClose(guard, context, entry, target)
        assertTrue(hookRan)
    }

    @Test
    fun inspectionErrorsPropagateUnchanged() =
        withOwnedFile { _, entry, _ ->
        val failures = listOf(
            AccessDeniedException(entry.toString(), null, "EACCES"),
            FileSystemException(entry.toString(), null, "EPERM"),
            IOException("readlink I/O failure"),
            IllegalStateException("unexpected inspection failure"),
        )
        for (failure in failures) {
            val guard = ForeignFdGuard(ROOT) { path ->
                if (path == entry) throw failure
                Files.readSymbolicLink(path)
            }
            assertSame(failure, assertThrows(failure.javaClass) { guard.beforeEach(context()) })
        }
    }

    @Test
    fun missingInspectionRootFails() {
        val root = Files.createTempDirectory("fdguard-missing-root")
        Files.delete(root)
        val failure = assertThrows(NoSuchFileException::class.java) {
            ForeignFdGuard(root, Files::readSymbolicLink).beforeEach(context())
        }
        assertEquals(root.toString(), failure.file)
    }

    @Test
    fun inspectionRootDisappearingDuringReadlinkFails() =
        withOwnedFile { _, entry, _ ->
        val root = Files.createTempDirectory("fdguard-vanishing-root")
        val link = root.resolve(entry.fileName)
        try {
            Files.createSymbolicLink(link, entry)
            var hookRan = false
            val guard = ForeignFdGuard(root) { path ->
                hookRan = true
                Files.delete(link)
                Files.delete(root)
                Files.readSymbolicLink(path)
            }
            val failure = assertThrows(NoSuchFileException::class.java) { guard.beforeEach(context()) }
            assertTrue(hookRan)
            assertEquals(root.toString(), failure.file)
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(root)
        }
    }

    private fun assertForeignClose(
        guard: ForeignFdGuard,
        context: ExtensionContext,
        entry: Path,
        target: String,
    ) {
        val failure = assertThrows(AssertionError::class.java) { guard.afterEach(context) }
        assertTrue(failure.message!!.contains("fd ${entry.fileName} -> $target"), failure.message)
    }

    private fun withOwnedFile(action: (FileChannel, Path, String) -> Unit) {
        val file = Files.createTempFile("fdguard-owned-", ".tmp")
        try {
            FileChannel.open(file, StandardOpenOption.READ).use { owner ->
                val target = file.toString()
                val entry = Files.list(ROOT).use { stream ->
                    stream
                        .filter { path ->
                        try {
                            Files.readSymbolicLink(path).toString() == target
                        } catch (_: NoSuchFileException) {
                            false
                        }
                    }.toList()
                        .single()
                }
                action(owner, entry, target)
            }
        } finally {
            Files.delete(file)
        }
    }

    private fun context(): ExtensionContext = testContext

    companion object {
        private val ROOT = Path.of("/proc/self/fd")
    }
}
