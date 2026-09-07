package io.mazewall.testing

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine

/**
 * Runs [block] with [engine] installed and always restores the production native engine.
 *
 * This scope is intentionally limited to the native-engine seam. Tests that alter
 * module-specific state must keep that setup and cleanup beside the scenario.
 */
public inline fun <T> withNativeEngine(
    engine: NativeEngine,
    block: () -> T,
): T {
    LinuxNative.setEngine(engine)
    return try {
        block()
    } finally {
        LinuxNative.resetToDefault()
    }
}
