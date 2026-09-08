import io.mazewall.build.BuildPolicy

group = BuildPolicy.GROUP
version = BuildPolicy.VERSION

configurations.configureEach {
    if (
        isCanBeResolved &&
            (name.endsWith("RuntimeClasspath") || name in setOf("detekt", "jacocoAgent", "jacocoAnt", "ktlint", "spotbugs", "spotbugsPlugins"))
    ) {
        resolutionStrategy.activateDependencyLocking()
    }
}
