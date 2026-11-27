package io.mazewall.platform.lifecycle

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Fails any test that closes a file descriptor which already existed before the
 * test started.
 *
 * Rationale (2026-08-24 incident): FD-lifecycle tests minted tokens around
 * INVENTED integers and called close(int) on them - real syscalls in the shared
 * worker JVM. Whatever resource held that integer was destroyed (the lazily
 * opened /dev/urandom fd, gradle pipes), surfacing later as EBADF in unrelated
 * SecureRandom users like JUnit @TempDir, moving nondeterministically between
 * failure sites.
 *
 * Invariant enforced: descriptors present before a test are foreign; tests may
 * only close what they opened themselves. Anything missing after the test is a
 * violation named by fd number and target (readlink of /proc/self/fd/N).
 * [GuardProcessDescriptors] reserves the resource across both snapshots through
 * JUnit's scheduler. Concurrent child probes declare [UsesProcessDescriptors].
 *
 * Disable temporarily with -Dmazewall.fdguard=off (diagnostics only - never to
 * make a failing test pass).
 */
class ForeignFdGuard internal constructor(
    private val inspectionRoot: Path,
    private val readLink: (Path) -> Path,
) : BeforeEachCallback,
    AfterEachCallback {
    constructor() : this(Path.of("/proc/self/fd"), Files::readSymbolicLink)

    override fun beforeEach(context: ExtensionContext) {
        if (isDisabled()) return
        context.getStore(NAMESPACE).put(FD_KEY, Snapshot(currentFds()))
    }

    override fun afterEach(context: ExtensionContext) {
        val before = context.getStore(NAMESPACE).remove(FD_KEY, Snapshot::class.java) ?: return
        val vanished = before.descriptors - currentFds()
        if (vanished.isNotEmpty()) {
            val detail = vanished.joinToString("\n  ") { "fd ${it.id} -> ${it.target}" }
            throw AssertionError(
                "ForeignFdGuard: test '${context.displayName}' closed ${vanished.size} " +
                    "descriptor(s) it did not own:\n  $detail\n" +
                    "Mint tokens only around integers obtained from real opens this test owns.",
            )
        }
    }

    private data class Snapshot(
        val descriptors: Set<FdInfo>,
    )

    private data class FdInfo(
        val id: Int,
        val target: String,
    )

    private fun currentFds(): Set<FdInfo> =
        Files
            .list(inspectionRoot)
            .use { stream ->
            val inspectionDirectory = "/proc/${ProcessHandle.current().pid()}/fd"
            stream
                .map { p ->
                    val id = p.fileName.toString().toInt()
                    descriptorTarget(p)?.let { target -> FdInfo(id, target) }
                }.filter { it != null && it.target != inspectionDirectory }
                .toList()
                .filterNotNull()
        }.filter { fd -> descriptorTarget(inspectionRoot.resolve(fd.id.toString())) == fd.target }
            .toSet()

    private fun descriptorTarget(path: Path): String? =
        try {
            readLink(path).toString()
        } catch (_: NoSuchFileException) {
            // An entry race is acceptable only while the inspection root is still present.
            Files.readAttributes(inspectionRoot, BasicFileAttributes::class.java)
            null
        }

    private fun isDisabled(): Boolean = System.getProperty("mazewall.fdguard")?.lowercase() == "off"

    companion object {
        private val NAMESPACE = Namespace.create(ForeignFdGuard::class.java)
        private val FD_KEY = "fds"
    }
}
