package demo.vulnapp.controller

import demo.vulnapp.service.XmlImportService
import demo.vulnapp.service.YamlImportService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class ImportController(
    private val yamlImportService: YamlImportService,
    private val xmlImportService: XmlImportService
) {

    @PostMapping("/import/yaml")
    fun importYaml(@RequestBody body: String): String {
        return yamlImportService.importYaml(body)
    }

    @PostMapping("/import/xstream")
    fun importXStream(@RequestBody body: String): String {
        return xmlImportService.importXStream(body)
    }

    @PostMapping("/import/xxe")
    fun importXXE(@RequestBody body: String): String {
        return xmlImportService.importXXE(body)
    }
}
