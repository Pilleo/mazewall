package demo.vulnapp.controller

import org.apache.logging.log4j.LogManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ExecutorService
import org.springframework.beans.factory.annotation.Qualifier

@RestController
class AdminController(
    @Qualifier("loggingExecutor") private val loggingExecutor: ExecutorService
) {
    companion object {
        init {
            System.setProperty("log4j2.formatMsgNoLookups", "false")
            System.setProperty("log4j2.enableJndiLookup", "true")
            System.setProperty("log4j2.enableJndiLdap", "true")
            // Legacy/alternative property names
            System.setProperty("log4j2.enableJndi", "true")
        }
    }

    private val logger = LogManager.getLogger(AdminController::class.java)

    @GetMapping("/admin/log")
    fun logMessage(
        @RequestHeader(value = "X-Api-Version", required = false, defaultValue = "1.0") apiVersion: String
    ): String {
        return loggingExecutor.submit<String> {
            // Log4Shell: direct logging of untrusted user header input using Log4j
            logger.info("Processing admin request. API Version: " + apiVersion)
            "Log processed: $apiVersion"
        }.get()
    }
}
