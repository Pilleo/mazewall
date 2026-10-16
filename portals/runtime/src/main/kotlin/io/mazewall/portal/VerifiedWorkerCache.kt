package io.mazewall.portal

import java.nio.file.Files
import java.nio.file.Path

/** One cache-root validation path shared by resource copying and ZIP extraction. */
internal object VerifiedWorkerCache {
    fun directory(configured: Path): Path {
        val path = configured.toAbsolutePath().normalize()
        requireNoSymlink(path)
        Files.createDirectories(path)
        requireNoSymlink(path)
        require(Files.isDirectory(path)) { "worker cache root is not a real directory: $path" }
        return path
    }

    private fun requireNoSymlink(path: Path) {
        var component: Path? = path
        while (component != null) {
            require(!Files.isSymbolicLink(component)) { "worker cache contains a symbolic link: $component" }
            component = component.parent
        }
    }
}
