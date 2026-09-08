package io.mazewall.core

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import java.nio.file.Files
import java.nio.file.Path

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
 *
 * Disable temporarily with -Dmazewall.fdguard=off (diagnostics only - never to
 * make a failing test pass).
 */
class ForeignFdGuard :
    BeforeEachCallback,
    AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        if (isDisabled()) return
        context.getStore(NAMESPACE).put(FD_KEY, currentFds())
    }

    override fun afterEach(context: ExtensionContext) {
        if (isDisabled()) return
        val before = context.getStore(NAMESPACE).remove(FD_KEY, Set::class.java) as? Set<FdInfo> ?: return
        val after = currentFds()
        val beforeById = before.associateBy { it.id }
        val vanished = before.filterNot { candidate ->
            after.any { it.id == candidate.id && it.target == candidate.target }
        }
        if (vanished.isNotEmpty()) {
            val detail = vanished.joinToString("\n  ") {
                "fd ${it.id} -> ${it.target} (${beforeById[it.id]?.target ?: "?"})"
            }
            throw AssertionError(
                "ForeignFdGuard: test '${context.displayName}' closed ${vanished.size} " +
                    "descriptor(s) it did not own:\n  $detail\n" +
                    "Mint tokens only around integers obtained from real opens this test owns.",
            )
        }
    }

    private data class FdInfo(
        val id: Int,
        val target: String,
    )

    private fun currentFds(): Set<FdInfo> =
        Files.list(Path.of("/proc/self/fd")).use { stream ->
            stream
                .map { p ->
                    val id = p.fileName.toString().toInt()
                    // An FD may close at any point while /proc/self/fd is being
                    // enumerated. Re-read the target below so only entries that
                    // remain stable for the full snapshot can be audited.
                    FdInfo(id, targetFor(p))
                }.toList()
                // The directory stream owns an fd pointing at this directory. It is
                // necessarily closed when the snapshot completes, so it is not a
                // descriptor the test could have closed.
                .filter { fd ->
                    fd.target != UNREADABLE_TARGET &&
                        fd.target != SELF_FD_DIRECTORY_TARGET &&
                        targetFor(Path.of("/proc/self/fd", fd.id.toString())) == fd.target
                }.toSet()
        }

    private fun targetFor(path: Path): String = runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() ?: UNREADABLE_TARGET

    private fun isDisabled(): Boolean = System.getProperty("mazewall.fdguard")?.lowercase() == "off"

    companion object {
        private val NAMESPACE = Namespace.create(ForeignFdGuard::class.java)
        private val FD_KEY = "fds"
        private val SELF_FD_DIRECTORY_TARGET = "/proc/${ProcessHandle.current().pid()}/fd"
        private const val UNREADABLE_TARGET = "<unreadable>"
    }
}
