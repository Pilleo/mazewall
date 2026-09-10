package demo

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class DemoFixture private constructor(
    val root: Path,
    val marker: File,
) : AutoCloseable {
    override fun close() {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
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
