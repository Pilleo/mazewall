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
 * only close what they opened themselves. A missing or changed descriptor in
 * the verified after snapshot is reported with its fd number and target.
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
        context.getStore(NAMESPACE).put(FD_KEY, currentFds())
    }

    override fun afterEach(context: ExtensionContext) {
        if (isDisabled()) return
        val before = context.getStore(NAMESPACE).remove(FD_KEY, Snapshot::class.java) ?: return
        val after = currentFds()
        val differences = before.targets.toSortedMap().mapNotNull { (id, previousTarget) ->
            when (val currentTarget = after.targets[id]) {
                previousTarget -> null
                null -> "fd $id missing from after snapshot (previous target $previousTarget)"
                else -> "fd $id changed target from $previousTarget to $currentTarget"
            }
        }
        if (differences.isNotEmpty()) {
            val detail = differences.joinToString("\n  ")
            throw AssertionError(
                "ForeignFdGuard: test '${context.displayName}' changed ${differences.size} " +
                    "foreign descriptor observation(s):\n  $detail\n" +
                    "Mint tokens only around integers obtained from real opens this test owns.",
            )
        }
    }

    private data class FdInfo(
        val id: Int,
        val target: Path,
    )

    private class Snapshot(
        val targets: Map<Int, Path>,
    )

    private fun currentFds(): Snapshot {
        val inspectionDirectory = Path.of("/proc/${ProcessHandle.current().pid()}/fd")
        val candidates = Files
            .list(inspectionRoot)
            .use { stream ->
                stream
                    .map { path ->
                        val id = path.fileName.toString().toInt()
                        descriptorTarget(path)?.let { target -> FdInfo(id, target) }
                    }.filter { it != null && it.target != inspectionDirectory }
                    .toList()
                    .filterNotNull()
            }
        val verified = candidates.filter { fd -> descriptorTarget(inspectionRoot.resolve(fd.id.toString())) == fd.target }
        return Snapshot(verified.associate { it.id to it.target })
    }

    private fun descriptorTarget(path: Path): Path? =
        try {
            readLink(path)
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
