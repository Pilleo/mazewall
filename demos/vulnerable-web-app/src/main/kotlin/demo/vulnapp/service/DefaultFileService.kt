package demo.vulnapp.service

import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class DefaultFileService(private val destDir: File = File("/app/uploads")) : FileService {
    override fun extractZip(file: MultipartFile): String {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        ZipInputStream(file.inputStream).use { zipIn ->
            var entry = zipIn.getNextEntry()
            val filesExtracted = mutableListOf<String>()

            while (entry != null) {
                // Zip Slip Vulnerability: path is resolved directly without validating against destDir escape!
                val filePath = File(destDir, entry.name).canonicalFile

                // If it is a directory, create it
                if (entry.isDirectory) {
                    filePath.mkdirs()
                } else {
                    // Ensure the parent directories exist
                    filePath.parentFile?.mkdirs()
                    FileOutputStream(filePath).use { fos ->
                        zipIn.transferTo(fos)
                    }
                    filesExtracted.add(filePath.name)
                }
                zipIn.closeEntry()
                entry = zipIn.getNextEntry()
            }
            return "Extracted zip entries: ${filesExtracted.joinToString()}"
        }
    }

    override fun runCommand(cmd: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        try {
            process.waitFor()
            return "Executed: $cmd"
        } finally {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
    }
}
