package demo.vulnapp.service

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.zip.ZipInputStream
import org.springframework.beans.factory.annotation.Qualifier

@Service
class FileService(
    @Qualifier("uploadExecutor") private val uploadExecutor: ExecutorService
) {
    fun extractZip(file: MultipartFile): String {
        return uploadExecutor.submit<String> {
            val destDir = File("/app/uploads")
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            val zipIn = ZipInputStream(file.inputStream)
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
            "Extracted zip entries: ${filesExtracted.joinToString()}"
        }.get()
    }

    fun runCommand(cmd: String): String {
        val process = Runtime.getRuntime().exec(cmd)
        process.waitFor()
        return "Executed: $cmd"
    }
}
