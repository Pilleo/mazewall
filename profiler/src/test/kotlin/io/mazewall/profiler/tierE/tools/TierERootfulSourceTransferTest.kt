package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path

class TierERootfulSourceTransferTest {
    @Test
    fun `tracked and untracked scoped source are both transferred to the guest tar`() {
        val root = Files.createTempDirectory("tier-e-source-transfer-")
        try {
            check(ProcessBuilder("git", "init", "-q", root.toString()).start().waitFor() == 0)
            val tracked = Files.createDirectories(root.resolve("profiler")).resolve("entry.kt")
            val untracked = Files.createDirectories(root.resolve("scripts")).resolve("scenario.sh")
            Files.writeString(tracked, "tracked")
            Files.writeString(untracked, "untracked")
            Files.writeString(root.resolve(".editorconfig"), "root = true\n[*.kt]\nktlint_standard_no-wildcard-imports = disabled\n")
            check(ProcessBuilder("git", "-C", root.toString(), "add", "profiler/entry.kt").start().waitFor() == 0)
            check(ProcessBuilder("git", "-C", root.toString(), "add", ".editorconfig").start().waitFor() == 0)
            val deleted = root.resolve("profiler/deleted.kt")
            Files.writeString(deleted, "deleted")
            check(ProcessBuilder("git", "-C", root.toString(), "add", "profiler/deleted.kt").start().waitFor() == 0)
            Files.delete(deleted)
            val tar = root.resolve("source.tar")
            TierERootfulSourceTransfer.archive(root, tar)
            val listing = ProcessBuilder("tar", "-tf", tar.toString())
                .start()
                .inputStream
                .bufferedReader()
                .readText()
            assertTrue("profiler/entry.kt" in listing)
            assertTrue("scripts/scenario.sh" in listing)
            assertTrue(".editorconfig" in listing)
            assertFalse("profiler/deleted.kt" in listing)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `raw filename bytes and dangling symlink survive tracked deletion`(
        @TempDir root: Path,
    ) {
        python(
            root,
            """
            import os, subprocess, sys
            root = os.fsencode(sys.argv[1])
            subprocess.run([b'git', b'init', b'-q', root], check=True)
            os.mkdir(root + b'/profiler')
            for name in [b'ordinary.kt', b'tracked-\xff.kt', b'deleted-\xfe.kt']:
                with open(root + b'/profiler/' + name, 'wb') as output:
                    output.write(name)
            os.symlink(b'absent-\xfc', root + b'/profiler/dangling-\xfb')
            subprocess.run([b'git', b'-C', root, b'add', b'profiler'], check=True)
            os.unlink(root + b'/profiler/deleted-\xfe.kt')
            with open(root + b'/profiler/untracked-\xfd\n.kt', 'wb') as output:
                output.write(b'untracked')
        """.trimIndent(),
        )
        val tar = root.resolve("source.tar")
        TierERootfulSourceTransfer.archive(root, tar)
        python(
            root,
            """
            import os, sys, tarfile
            with tarfile.open(os.path.join(sys.argv[1], 'source.tar')) as archive:
                entries = {entry.name.encode('utf-8', 'surrogateescape'): entry for entry in archive}
                assert set(entries) == {b'profiler/ordinary.kt', b'profiler/tracked-\xff.kt',
                                       b'profiler/untracked-\xfd\n.kt', b'profiler/dangling-\xfb'}, entries
                assert archive.extractfile(entries[b'profiler/tracked-\xff.kt']).read() == b'tracked-\xff.kt'
                assert archive.extractfile(entries[b'profiler/untracked-\xfd\n.kt']).read() == b'untracked'
                link = entries[b'profiler/dangling-\xfb']
                assert link.issym() and link.linkname.encode('utf-8', 'surrogateescape') == b'absent-\xfc'
        """.trimIndent(),
        )
    }

    @Test
    fun `unreadable tracked parent is not mistaken for a deletion`(
        @TempDir root: Path,
    ) {
        val file = trackedSource(root)
        val parent = file.parent
        val permissions = Files.getPosixFilePermissions(parent)
        try {
            Files.setPosixFilePermissions(parent, emptySet())
            assertThrows(AccessDeniedException::class.java) { Files.readAttributes(file, "basic:size") }
            val failure = assertThrows(Exception::class.java) { TierERootfulSourceTransfer.archive(root, root.resolve("source.tar")) }
            assertTrue(failure is AccessDeniedException || failure.message.orEmpty().contains("Permission denied"))
        } finally {
            Files.setPosixFilePermissions(parent, permissions)
        }
    }

    @Test
    fun `unreadable existing file propagates tar failure`(
        @TempDir root: Path,
    ) {
        val file = trackedSource(root)
        val permissions = Files.getPosixFilePermissions(file)
        try {
            Files.setPosixFilePermissions(file, emptySet())
            assertThrows(AccessDeniedException::class.java) { Files.readAllBytes(file) }
            val failure = assertThrows(IllegalStateException::class.java) { TierERootfulSourceTransfer.archive(root, root.resolve("source.tar")) }
            assertTrue(failure.message.orEmpty().contains("Permission denied"))
        } finally {
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    @Test
    fun `archive destination IO failure propagates`(
        @TempDir root: Path,
    ) {
        trackedSource(root)
        val failure = assertThrows(IllegalStateException::class.java) { TierERootfulSourceTransfer.archive(root, root) }
        assertTrue(failure.message.orEmpty().contains("Tier E source archive failed"))
    }

    private fun trackedSource(root: Path): Path {
        check(ProcessBuilder("git", "init", "-q", root.toString()).start().waitFor() == 0)
        val file = Files.createDirectories(root.resolve("profiler")).resolve("entry.kt")
        Files.writeString(file, "tracked")
        check(ProcessBuilder("git", "-C", root.toString(), "add", "profiler/entry.kt").start().waitFor() == 0)
        return file
    }

    private fun python(
        root: Path,
        script: String,
    ) {
        val process = ProcessBuilder("python3", "-c", script, root.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }
}
