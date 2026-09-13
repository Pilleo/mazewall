package io.mazewall.portal.worker

import io.mazewall.portal.PortalServiceManifestCatalog

/** Resolves generated manifests and dispatchers before a worker initializes a guest implementation. */
internal fun generatedManifestEntries(loader: ClassLoader): List<String> {
    val manifests = PortalServiceManifestCatalog.load(loader)
    manifests
        .groupBy { it.isolationGroup }
        .forEach { (group, members) ->
            val launchShapes = members.map { Triple(it.workerArtifact, it.policyProfile, it.instanceMode) }.toSet()
            require(launchShapes.size == 1) {
                "portal worker isolation group $group has incompatible worker artifact, policy profile, or instance mode"
            }
        }
    return manifests.map { manifest ->
        val resourceName = "META-INF/mazewall/portal/${manifest.contractName}"
        val resources = loader.getResources(resourceName).toList()
        require(resources.size == 1) {
            "portal worker requires exactly one generated dispatcher descriptor for ${manifest.contractName}; " +
                "package the portalWorkerJar on the worker classpath"
        }
        val dispatcher = resources.single().openStream().use { stream ->
            val bytes = stream.readNBytes(4097)
            require(bytes.size <= 4096) { "oversized portal dispatcher descriptor for ${manifest.contractName}" }
            bytes.toString(Charsets.UTF_8).trim()
        }
        require(dispatcher == manifest.dispatcherName) {
            "portal service ${manifest.serviceId.value} dispatcher descriptor conflicts with its manifest"
        }
        "${manifest.contractName}=${manifest.implementationName};$dispatcher"
    }
}

/** Strictly publish every generated manifest entry before the worker accepts a request. */
internal fun PortalDispatcherTable.Builder.bootstrapGeneratedManifests(loader: ClassLoader): Int {
    val entries = generatedManifestEntries(loader)
    if (entries.isEmpty()) return 0
    val manifests = PortalServiceManifestCatalog.load(loader)
    val factories =
        manifests
            .map { manifest ->
                manifest.implementationName to
                    PortalReflectiveWorkerFactory()
            }.toMap()
    val services = PortalWorkerServiceRegistry(manifests, factories, loader)
    manifests.forEach { manifest ->
        val implementation = services.group(manifest.isolationGroup).getValue(manifest.serviceId.value)
        registerGeneratedManifest(manifest, implementation, loader)
    }
    return entries.size
}

/** Generated manifests are authoritative; the property remains a migration-only fallback. */
internal fun bootstrapWorkerDispatchers(
    builder: PortalDispatcherTable.Builder,
    legacyEntries: String?,
    loader: ClassLoader,
): Int {
    val manifestEntries = builder.bootstrapGeneratedManifests(loader)
    return if (manifestEntries > 0) manifestEntries else builder.bootstrapGenerated(legacyEntries, loader)
}

/** Resolves generated metadata only through the worker's defining loader, before guest construction. */
internal fun PortalDispatcherTable.Builder.bootstrapGenerated(
    raw: String?,
    loader: ClassLoader,
): Int {
    requireActive()
    if (raw.isNullOrBlank()) return 0
    val entries = raw.split(',').map { entry ->
        val pair = entry.trim().split('=')
        require(pair.size == 2) { "expected Service=Implementation for generated worker entry" }
        val service = pair[0].trim()
        require(service.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*"))) { "invalid portal service name: $service" }
        val implementation = pair[1].substringBefore(';').trim()
        val resources = loader.getResources("META-INF/mazewall/portal/$service").toList()
        require(resources.size == 1) {
            "portal worker requires exactly one generated dispatcher descriptor for $service; package the portalWorkerJar on the worker classpath"
        }
        val dispatcher = resources.single().openStream().use { stream ->
            val bytes = stream.readNBytes(4097)
            require(bytes.size <= 4096) { "oversized portal dispatcher descriptor for $service" }
            bytes.toString(Charsets.UTF_8).trim()
        }
        require(dispatcher.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*"))) { "invalid portal dispatcher descriptor for $service" }
        if (';' in pair[1]) {
            require(pair[1].substringAfter(';').trim() == dispatcher) { "explicit dispatcher conflicts with generated descriptor for $service" }
        }
        "$service=$implementation;$dispatcher"
    }
    entries.forEach { registerGeneratedEntry(it, loader) }
    return entries.size
}
