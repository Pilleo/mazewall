package io.mazewall.portal.worker

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
