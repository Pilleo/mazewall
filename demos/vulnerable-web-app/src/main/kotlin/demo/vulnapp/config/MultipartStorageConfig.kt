package demo.vulnapp.config

import jakarta.servlet.MultipartConfigElement
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Framework upload inputs belong to the same declared resource as file-service work. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(MultipartProperties::class)
internal class MultipartStorageConfig(
    private val resources: ServiceResourceBindings,
) {
    @Bean
    fun multipartConfigElement(properties: MultipartProperties): MultipartConfigElement {
        val uploads = resources.uploads.toRealPath()
        val staging = uploads.resolve(".multipart")
        val configuredLocation = properties.location
        require(configuredLocation.isNullOrBlank() || Path.of(configuredLocation).toAbsolutePath().normalize() == staging) {
            "Multipart location must use the declared upload staging resource: $staging"
        }
        try {
            Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: FileAlreadyExistsException) {
            require(Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) { "Multipart staging must be an existing real directory: $staging" }
        }
        val limits = properties.createMultipartConfig()
        return MultipartConfigElement(staging.toString(), limits.maxFileSize, limits.maxRequestSize, limits.fileSizeThreshold)
    }
}
