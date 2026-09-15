package io.mazewall.platform.process

/**
 * Owns [ProcessHandle.descendants] snapshots. The JDK stream holds a kernel view and
 * must be closed; call these helpers instead of using the stream as a fire-and-forget
 * iterable.
 */
public fun Process.destroyForciblyWithDescendants() {
    toHandle().snapshotDescendants().asReversed().forEach(ProcessHandle::destroyForcibly)
    destroyForcibly()
}

public fun ProcessHandle.snapshotDescendants(): List<ProcessHandle> = descendants().use { children -> children.toList() }
