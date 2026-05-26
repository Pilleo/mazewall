package demo.vulnapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VulnAppApplication

fun main(args: Array<String>) {
    System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
    System.setProperty("log4j2.formatMsgNoLookups", "false")
    System.setProperty("log4j2.enableJndiLookup", "true")
    System.setProperty("log4j2.enableJndiLdap", "true")
    System.setProperty("log4j2.enableJndi", "true")
    runApplication<VulnAppApplication>(*args)
}
