package demo.vulnapp.service

import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.concurrent.ExecutorService
import org.springframework.beans.factory.annotation.Qualifier

@Service
class ProxyService(
    @Qualifier("proxyExecutor") private val proxyExecutor: ExecutorService
) {
    private val restTemplate = RestTemplate()

    fun fetchUrl(url: String): String {
        return proxyExecutor.submit<String> {
            try {
                restTemplate.getForObject(url, String::class.java) ?: "Empty response"
            } catch (e: Exception) {
                "Error fetching URL: ${e.message}"
            }
        }.get()
    }
}
