package demo.vulnapp.service

import com.thoughtworks.xstream.XStream
import org.springframework.stereotype.Service
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutorService
import javax.xml.parsers.DocumentBuilderFactory
import org.springframework.beans.factory.annotation.Qualifier

@Service
class XmlImportService(
    @Qualifier("importExecutor") private val importExecutor: ExecutorService
) {
    fun importXStream(xmlContent: String): String {
        return importExecutor.submit<String> {
            val xstream = XStream()
            // Configure XStream security to allow all classes (simulating vulnerable defaults)
            XStream.setupDefaultSecurity(xstream)
            xstream.allowTypesByWildcard(arrayOf("**"))
            val obj = xstream.fromXML(xmlContent)
            "Deserialized with XStream: $obj (${obj?.javaClass?.name})"
        }.get()
    }

    fun importXXE(xmlContent: String): String {
        return importExecutor.submit<String> {
            val dbf = DocumentBuilderFactory.newInstance()
            // Vulnerable defaults (XXE enabled by default in Java Standard Library)
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(ByteArrayInputStream(xmlContent.toByteArray()))
            val root = doc.documentElement
            "Parsed XML. Root tag: ${root.tagName}, content: ${root.textContent.trim()}"
        }.get()
    }
}
