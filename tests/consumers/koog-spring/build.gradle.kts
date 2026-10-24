import java.io.File

plugins { kotlin("jvm"); kotlin("plugin.serialization") }

kotlin { jvmToolchain(25) }

val candidate = providers.gradleProperty("consumerVersion").get()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+[-.A-Za-z0-9]*").matches(candidate)) { "Pinned safe consumer version required" }
val repositoryPath = File(providers.gradleProperty("consumerRepository").get())
require(repositoryPath.isAbsolute && repositoryPath.isDirectory) { "Existing explicit publication repository required" }
val repository = repositoryPath.canonicalFile

repositories {
    maven { url = uri(repository); content { includeGroup("io.mazewall") } }
    mavenCentral { content { excludeGroup("io.mazewall") } }
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
    lockFile.set(layout.projectDirectory.file("locks/$candidate/gradle.lockfile"))
}

dependencies {
    implementation("io.mazewall:koog:$candidate")
    implementation("io.mazewall:mazewall-spring-boot-starter:$candidate")
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.security:spring-security-core")
    implementation("com.h2database:h2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    minHeapSize = "128m"
    maxHeapSize = "256m"
}

tasks.register("verifyPublishedClosure") {
    val selected = configurations.named("runtimeClasspath")
    inputs.files(selected)
    doLast {
        val graph = selected.get()
        val rootId = graph.incoming.resolutionResult.rootComponent.get().id
        check(graph.incoming.resolutionResult.allComponents.none {
            it.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier && it.id != rootId
        }) { "Published consumer cannot select sibling source projects" }
        graph.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.mazewall" }.forEach {
            check(it.moduleVersion.id.version == candidate && it.file.canonicalPath.startsWith(repository.canonicalPath + File.separator)) {
                "First-party artifact does not belong to the frozen publication repository/version"
            }
            println("PUBLISHED ${it.moduleVersion.id} ${it.file.name}")
        }
    }
}
