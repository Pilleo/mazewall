package demo.vulnapp.controller

import demo.vulnapp.service.FileService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class FileController(
    private val fileService: FileService
) {

    @PostMapping("/command")
    fun runCommand(@RequestParam("cmd") command: String): String = fileService.runCommand(command)

    @PostMapping("/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): String {
        return fileService.extractZip(file)
    }
}
