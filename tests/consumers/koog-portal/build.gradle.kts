import java.io.File
import java.nio.file.Files

plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(25) }
val candidate = providers.gradleProperty("consumerVersion").get()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+[-.A-Za-z0-9]*").matches(candidate)) { "Pinned safe consumer version required" }
val repository = providers.gradleProperty("consumerRepository").get()
repositories {
    maven { url = uri(repository); content { includeGroup("io.mazewall") } }
    mavenCentral { content { excludeGroup("io.mazewall") } }
}
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
    if (candidate != "0.1.0-task10") lockFile.set(layout.projectDirectory.file("locks/$candidate/gradle.lockfile"))
}
dependencies {
    implementation("io.mazewall:koog-contained-tool:$candidate")
    implementation("io.mazewall:denied-api:$candidate")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("ai.koog:agents-core:1.3.0")
}

tasks.register<JavaExec>("nativeProbe") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.mazewall.consumer.koog.KoogPortalProbeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    environment("LC_ALL", "C")
}

tasks.register<JavaExec>("verifyOperationRoot") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.mazewall.consumer.koog.OperationRootProbeKt")
}

tasks.register<Sync>("stageFreshProbe") {
    dependsOn("jar")
    from(sourceSets.main.get().runtimeClasspath.filter { it.isFile && it.extension == "jar" })
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("fresh-probe/lib"))
}

tasks.register<JavaExec>("verifyFreshNative") {
    dependsOn("stageFreshProbe", "verifyPublishedClosure")
    classpath = fileTree(layout.buildDirectory.dir("fresh-probe/lib")) { include("*.jar") }
    mainClass.set("io.mazewall.consumer.koog.KoogPortalProbeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    environment("LC_ALL", "C")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(classpath.files.isNotEmpty() && classpath.files.all { it.isFile && it.extension == "jar" }) {
            "Fresh native consumer must use staged JARs only"
        }
        workingDir = Files.createTempDirectory("mazewall-koog-native-").toFile()
        logger.lifecycle("FRESH_NATIVE_CONSUMER directory={} jars={}", workingDir, classpath.files.size)
    }
}

tasks.register<JavaExec>("verifyFreshThreadNative") {
    dependsOn("stageFreshProbe", "verifyPublishedClosure")
    classpath = fileTree(layout.buildDirectory.dir("fresh-probe/lib")) { include("*.jar") }
    mainClass.set("io.mazewall.consumer.koog.KoogThreadProbeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    environment("LC_ALL", "C")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(classpath.files.isNotEmpty() && classpath.files.all { it.isFile && it.extension == "jar" }) {
            "Fresh native consumer must use staged JARs only"
        }
        workingDir = Files.createTempDirectory("mazewall-koog-thread-native-").toFile()
        logger.lifecycle("FRESH_THREAD_NATIVE_CONSUMER directory={} jars={}", workingDir, classpath.files.size)
    }
}

for ((task, entry) in listOf("verifyFreshThreadAgent" to "KoogThreadAgentProbeKt", "verifyFreshPortalAgent" to "KoogPortalAgentProbeKt")) {
    tasks.register<JavaExec>(task) {
        dependsOn("stageFreshProbe", "verifyPublishedClosure")
        classpath = fileTree(layout.buildDirectory.dir("fresh-probe/lib")) { include("*.jar") }
        mainClass.set("io.mazewall.consumer.koog.$entry")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        environment("LC_ALL", "C")
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        doFirst {
            require(classpath.files.isNotEmpty() && classpath.files.all { it.isFile && it.extension == "jar" })
            workingDir = Files.createTempDirectory("mazewall-koog-agent-").toFile()
        }
    }
}

tasks.register("verifyPublishedClosure") {
    val graph = configurations.runtimeClasspath.get()
    inputs.files(graph)
    doLast {
        val rootId = graph.incoming.resolutionResult.rootComponent.get().id
        check(graph.incoming.resolutionResult.allComponents.none { it.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier && it.id != rootId }) {
            "consumer must resolve published modules without sibling source builds"
        }
        graph.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.mazewall" }.forEach {
            check(it.moduleVersion.id.version == candidate)
            check(it.file.canonicalPath.startsWith(file(repository).canonicalPath + File.separator))
            println("PUBLISHED ${it.moduleVersion.id} ${it.file.name}")
        }
    }
}
