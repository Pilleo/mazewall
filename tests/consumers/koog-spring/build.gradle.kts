import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties

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
        val firstParty = graph.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.mazewall" }
        check(firstParty.map { it.name }.toSet() == setOf("platform", "enforcer", "runtime", "koog", "mazewall-spring-boot", "mazewall-spring-boot-starter")) {
            "Expected the six real published integration modules"
        }
        firstParty.forEach {
            check(it.moduleVersion.id.version == candidate && it.file.canonicalPath.startsWith(repository.canonicalPath + File.separator)) {
                "First-party artifact does not belong to the frozen publication repository/version"
            }
            println("PUBLISHED ${it.moduleVersion.id} ${it.file.name}")
        }
    }
}

val staged = layout.buildDirectory.dir("fresh-probe/lib")
tasks.register<Sync>("stageFreshProbe") {
    dependsOn("jar")
    from(configurations.runtimeClasspath)
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(staged)
}

for (caller in listOf("Platform", "Virtual")) {
    val kind = caller.lowercase()
    val proofRoot = layout.buildDirectory.dir("native-$kind")
    val packaged = fileTree(staged) { include("*.jar") }
    tasks.register<JavaExec>("measure${caller}Candidate") {
        dependsOn("stageFreshProbe", "verifyPublishedClosure")
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        classpath = packaged
        mainClass.set("consumer.koogspring.NativeKoogSpringConsumerKt")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m")
        environment("LC_ALL", "C")
        doFirst {
            val root = proofRoot.get().asFile
            Files.createDirectories(root.toPath())
            root.resolve("template.txt").writeText("approved template")
            root.resolve("sibling.txt").writeText("private sibling")
            check(classpath.files.isNotEmpty() && classpath.files.all { it.isFile && it.extension == "jar" })
            workingDir = root
            setArgs(listOf("measure", root.absolutePath, kind))
        }
    }
    tasks.register("approve${caller}Fixture") {
        dependsOn("measure${caller}Candidate")
        doLast {
            val root = proofRoot.get().asFile
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            // External authority for this fixed test fixture only, never production review.
            val authority = Properties().apply {
                setProperty("jarSha256", digest(staged.get().file(tasks.named<Jar>("jar").get().archiveFileName.get()).asFile))
                setProperty("documentSha256", digest(root.resolve("candidate.json")))
                setProperty("resourceGeneration", root.resolve("candidate-generation.txt").readText())
                setProperty("reviewer", "external-known-koog-spring-fixture-reviewer")
            }
            root.resolve("trusted-review.properties").outputStream().use { authority.store(it, "External test authority only") }
        }
    }
    tasks.register<JavaExec>("verifyFresh${caller}Native") {
        dependsOn("approve${caller}Fixture")
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        classpath = packaged
        mainClass.set("consumer.koogspring.NativeKoogSpringConsumerKt")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xms128m", "-Xmx256m")
        environment("LC_ALL", "C")
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        doFirst {
            check(classpath.files.isNotEmpty() && classpath.files.all { it.isFile && it.extension == "jar" })
            workingDir = proofRoot.get().asFile
            setArgs(listOf("verify", proofRoot.get().asFile.absolutePath, kind))
        }
    }
}

tasks.named("verifyFreshVirtualNative") { mustRunAfter("verifyFreshPlatformNative") }
tasks.register("verifyFreshNative") { dependsOn("verifyFreshPlatformNative", "verifyFreshVirtualNative") }
