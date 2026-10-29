import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.codegen.PortalCodegenExtension
import io.mazewall.portal.codegen.PortalServiceRegistration
import org.gradle.api.credentials.PasswordCredentials

plugins { kotlin("jvm"); id("io.mazewall.portal.codegen") }
kotlin { jvmToolchain(25) }
val version = providers.gradleProperty("consumerVersion").get()
repositories {
    maven {
        name = "mazewall"
        url = uri(providers.gradleProperty("consumerRepository").get())
        if (url.scheme == "https" && url.host == "maven.pkg.github.com") credentials(PasswordCredentials::class)
        content { includeGroup("io.mazewall") }
    }
    mavenCentral { content { excludeGroup("io.mazewall") } }
}
val apiSource = sourceSets.create("api")
val workerSource = sourceSets.create("portalWorker")
val apiJar = tasks.register<Jar>("apiJar") { from(apiSource.output); archiveFileName.set("boundary-api.jar") }
dependencies {
    implementation(files(apiJar))
    implementation("io.mazewall:runtime:$version")
    add(workerSource.implementationConfigurationName, files(apiJar))
    add(workerSource.implementationConfigurationName, "io.mazewall:worker:$version")
}
dependencyLocking { lockAllConfigurations(); lockMode.set(LockMode.STRICT); lockFile.set(layout.projectDirectory.file("locks/$version/gradle.lockfile")) }
configure<PortalCodegenExtension> {
    workerSourceSet.set("portalWorker")
    registrations.set(listOf(PortalServiceRegistration("boundary-probe", "demo.boundary.BoundaryProbeService",
        "demo.boundary.ControlledBoundaryProbe", "boundary-probe", PortalInstanceMode.STATEFUL_SINGLETON,
        "static-baseline", "document-boundary-worker")))
}
tasks.jar { archiveFileName.set("boundary-fixture.jar") }
