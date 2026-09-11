package demo.vulnapp

import demo.vulnapp.config.MazewallProtectionBootstrap
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class VulnAppApplication

fun main(args: Array<String>) {
    System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
    // Exploit-enabling JNDI switches are intentionally disabled for normal
    // application startup. The isolated vulnerable fixture enables them in
    // its own process, never in the standard demo launch.
    System.setProperty("log4j2.formatMsgNoLookups", "true")
    System.setProperty("log4j2.enableJndiLookup", "false")
    System.setProperty("log4j2.enableJndiLdap", "false")
    System.setProperty("log4j2.enableJndi", "false")
    SpringApplication(VulnAppApplication::class.java)
        .apply {
        addListeners(MazewallProtectionBootstrap())
    }.run(*args)
}
