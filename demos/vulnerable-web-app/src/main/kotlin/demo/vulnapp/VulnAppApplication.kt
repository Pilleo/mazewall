package demo.vulnapp

import demo.vulnapp.config.MazewallProtectionBootstrap
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class VulnAppApplication

fun main(args: Array<String>) {
    System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
    System.setProperty("log4j2.formatMsgNoLookups", "false")
    System.setProperty("log4j2.enableJndiLookup", "true")
    System.setProperty("log4j2.enableJndiLdap", "true")
    System.setProperty("log4j2.enableJndi", "true")
    SpringApplication(VulnAppApplication::class.java)
        .apply {
        addListeners(MazewallProtectionBootstrap())
    }.run(*args)
}
