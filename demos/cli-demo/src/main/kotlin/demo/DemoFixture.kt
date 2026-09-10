package demo

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal class DemoFixture private constructor(
    val root: Path,
    val marker: File,
) : AutoCloseable {
    override fun close() {
        root.toFile().deleteRecursively()
    }

    companion object {
        fun create(name: String = "scenario"): DemoFixture {
            val root = Files.createTempDirectory("mazewall-cli-demo-")
            val directory = root.toFile()
            directory.setReadable(false, false)
            directory.setWritable(false, false)
            directory.setExecutable(false, false)
            directory.setReadable(true, true)
            directory.setWritable(true, true)
            directory.setExecutable(true, true)
            return DemoFixture(root, root.resolve("$name-marker").toFile())
        }
    }
}
