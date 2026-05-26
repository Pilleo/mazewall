package demo.vulnapp.service

import org.springframework.stereotype.Service
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.inspector.TagInspector
import java.util.concurrent.ExecutorService
import org.springframework.beans.factory.annotation.Qualifier

@Service
class YamlImportService(
    @Qualifier("importExecutor") private val importExecutor: ExecutorService
) {
    fun importYaml(yamlContent: String): String {
        return importExecutor.submit<String> {
            val loaderOptions = LoaderOptions()
            // Make SnakeYAML 2.x unsafe by allowing arbitrary tags (deserialization gadget exploit)
            loaderOptions.tagInspector = TagInspector { true }
            val yaml = Yaml(Constructor(Any::class.java, loaderOptions))
            
            val obj = yaml.load<Any>(yamlContent)
            "Loaded object: $obj (${obj?.javaClass?.name})"
        }.get()
    }
}
