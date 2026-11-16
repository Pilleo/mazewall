package io.mazewall.platform.native

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

class NativeStackContextCompatibilityTest {
    @Test
    fun legacyBinary() {
        val fixture = requireNotNull(javaClass.getResource("/compatibility/legacy-native-engine.jar"))
        URLClassLoader(arrayOf(fixture), NativeEngine::class.java.classLoader).use { loader ->
            val type = loader.loadClass("io.mazewall.platform.native.LegacyNativeEngine")
            assertSame(loader, type.classLoader)
            assertEquals(fixture, type.protectionDomain.codeSource.location)
            assertSame(NativeEngine::class.java, type.interfaces.single())
            val engine = type.getDeclaredConstructor().newInstance() as NativeEngine
            assertSame(UnsupportedNativeStackContexts, engine.stackContextsOrUnsupported())
            val snapshot = RuntimeSnapshot(engine)
            assertSame(UnsupportedNativeStackContexts, snapshot.stackContextsOrUnsupported())
            assertSame(UnsupportedNativeStackContexts, snapshot.stackContexts)
        }
    }
}
