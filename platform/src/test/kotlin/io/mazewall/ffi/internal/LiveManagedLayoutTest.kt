package io.mazewall.ffi.internal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.Optional

class LiveManagedLayoutTest {
    private val managed = listOf("helper", "enforcer", "kotlin_stdlib", "serialization_core", "serialization_json", "jetbrains_annotations", "managed_fixture")
    private val native = listOf("agent", "helper", "java", "release", "modules", "libjvm", "libjava", "libnio", "libc", "loader")

    private fun identity(
        roles: List<String> = (native + managed).distinct(),
        paths: List<String> = managed.map { "/nonexistent/$it.jar" },
    ): LiveStackBootstrap.LiveDeploymentIdentity {
        val artifacts = roles.map { role -> LiveStackBootstrap.ArtifactIdentity(role, "/nonexistent/$role.jar", "0".repeat(64), "build", "source") }
        return LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "source",
            artifacts,
            emptyList(),
            paths,
            "helper",
            emptyList(),
            emptyList(),
            listOf(2),
            Optional.empty(),
        )
    }

    private fun prefix(identity: LiveStackBootstrap.LiveDeploymentIdentity) {
        val method = LiveStackBootstrap::class.java.getDeclaredMethod("checkManagedPrefix", LiveStackBootstrap.LiveDeploymentIdentity::class.java, Map::class.java, List::class.java).apply {
            isAccessible =
            true
        }
        val artifacts = identity.artifacts().associateBy { it.role() }
        method.invoke(null, identity, artifacts, identity.orderedClasspath())
    }

    class FixtureGood {
        companion object {
            @JvmStatic fun main(args: Array<String>) {
                require(args.isEmpty())
            }
        }
    }

    class FixtureWrong {
        companion object {
            @JvmStatic fun main(args: Array<String>): Int = args.size
        }
    }

    class BridgeGood {
        companion object {
            @JvmStatic fun run(args: Array<String>) {
                require(args.isEmpty())
            }
        }
    }

    class BridgeWrong {
        companion object {
            @JvmStatic fun run(args: Array<String>): Int = args.size
        }
    }

    @Test
    fun `named bootstrap module is rejected as a managed witness`() {
        val method = LiveStackBootstrap::class.java.getDeclaredMethod("checkManagedWitness", Class::class.java, String::class.java).apply { isAccessible = true }
        val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(null, String::class.java, "/not-inspected.jar") }
        assertEquals(UnsupportedOperationException::class.java, failure.cause?.javaClass)
    }

    @Test
    fun `fixture and bridge signature checks reject supplied wrong return types`() {
        for ((name, good, bad) in listOf(
            Triple("checkFixtureSignature", FixtureGood::class.java, FixtureWrong::class.java),
            Triple("checkBridgeSignature", BridgeGood::class.java, BridgeWrong::class.java),
        )) {
            val method = LiveStackBootstrap::class.java.getDeclaredMethod(name, Class::class.java).apply { isAccessible = true }
            method.invoke(null, good)
            val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(null, bad) }
            assertEquals(UnsupportedOperationException::class.java, failure.cause?.javaClass)
        }
    }

    @Test
    fun `native projection retains ten fixed pairs and route mask independent of managed roles`() {
        val all = (native + managed).distinct().associateWith { role ->
            LiveStackBootstrap.ArtifactIdentity(role, "/artifact/$role", "hash", "build:$role", "source")
        }
        val method = LiveStackBootstrap::class.java.getDeclaredMethod("nativeProjection", Map::class.java, List::class.java).apply { isAccessible = true }
        val expected = (native.flatMap { listOf("/artifact/$it", "build:$it") } + "3").toTypedArray()

        @Suppress("UNCHECKED_CAST")
        val withManaged = method.invoke(null, all, listOf(2, 257)) as Array<String>

        @Suppress("UNCHECKED_CAST")
        val nativeOnly = method.invoke(null, all.filterKeys { it in native }, listOf(2, 257)) as Array<String>
        assertArrayEquals(expected, withManaged)
        assertArrayEquals(expected, nativeOnly)
        assertEquals(21, withManaged.size)
        for ((routes, mask) in listOf(listOf(2) to "1", listOf(257) to "2")) {
            @Suppress("UNCHECKED_CAST")
            val projection = method.invoke(null, all, routes) as Array<String>
            assertEquals(mask, projection.last())
            assertArrayEquals(expected.dropLast(1).toTypedArray(), projection.dropLast(1).toTypedArray())
        }
    }

    @Test
    fun `old ten role candidate remains on original deployment checks`() {
        val method = LiveStackBootstrap::class.java.getDeclaredMethod("deploymentInputs", LiveStackBootstrap.LiveDeploymentIdentity::class.java, Int::class.javaPrimitiveType).apply {
            isAccessible =
            true
        }
        val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(null, identity(roles = native), 0) }
        assertEquals(UnsupportedOperationException::class.java, failure.cause?.javaClass)
        assertNotEquals("live deployment: managed expectations unavailable", failure.cause?.message)
    }

    @Test
    fun `finite production candidate refuses missing independent expectations before file inspection`() {
        val method = LiveStackBootstrap::class.java.getDeclaredMethod("deploymentInputs", LiveStackBootstrap.LiveDeploymentIdentity::class.java, Int::class.javaPrimitiveType).apply {
            isAccessible =
            true
        }
        val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(null, identity(), 0) }
        assertEquals("live deployment: managed expectations unavailable", failure.cause?.message)
    }

    @Test
    fun `finite candidate accepts exact structural prefix without touching filesystem`() {
        prefix(identity())
        assertEquals(0, LiveStackBootstrap::class.java.declaredFields.size)
    }

    @Test
    fun `missing extra duplicate and jointly reordered paths are rejected`() {
        val valid = identity()
        val invalid = listOf(
            identity(valid.artifacts().map { it.role() } - "enforcer"),
            identity(valid.artifacts().map { it.role() } + "extra"),
            identity(valid.artifacts().map { it.role() } + "helper"),
            identity(paths = valid.orderedClasspath().reversed()),
        )
        invalid.forEach { value ->
            val failure = assertThrows(InvocationTargetException::class.java) { prefix(value) }
            assertEquals(UnsupportedOperationException::class.java, failure.cause?.javaClass)
        }
    }

    @Test
    fun `v1 codec preserves explicit native role order`() {
        val expectedRoles = listOf(
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
            ),
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
                "libzip",
            ),
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
                "libsimdsort",
            ),
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
                "libzip",
                "libsimdsort",
            ),
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
                "libinstrument",
            ),
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
                "libzip",
                "libinstrument",
            ),
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
                "libsimdsort",
                "libinstrument",
            ),
            listOf(
                "agent",
                "java",
                "libjvm",
                "libjava",
                "libnio",
                "libc",
                "loader",
                "libstdc++",
                "libgcc_s",
                "libm",
                "libdl",
                "libpthread",
                "librt",
                "libnet",
                "libjli",
                "libjimage",
                "libmanagement",
                "libmanagement_ext",
                "libzip",
                "libsimdsort",
                "libinstrument",
            ),
        )
        val method = LiveStackBootstrap::class.java
            .getDeclaredMethod(
            "nativeProjectionV1",
            Map::class.java,
            LiveStackBootstrap.Profile::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply {
            isAccessible =
            true
        }
        for (profile in LiveStackBootstrap.Profile.entries) {
            for (branch in 0..3) {
                for (mask in 1..3) {
            val selected = expectedRoles[profile.ordinal * 4 + branch]
            val map = selected.associateWith { LiveStackBootstrap.ArtifactIdentity(it, "/native/$it", "hash", "ab", "source") }
            val actual = method.invoke(null, map, profile, mask, branch and 1 != 0, branch and 2 != 0) as Array<*>
            val head = listOf(
                "mazewall-t4/1",
                if (profile ==
                LiveStackBootstrap.Profile.CLOSED_V1
                ) {
                    "closed"
                } else {
                    "retransform"
                },
                    if (branch and 1 !=
                    0
                    ) {
                        "zip-present"
                    } else {
                        "zip-absent"
                    },
                        if (branch and 2 != 0) "simd-present" else "simd-absent",
                mask.toString(),
            )
            assertEquals(head + selected.flatMap { listOf(it, "/native/$it", "ab") }, actual.toList())
            if (profile == LiveStackBootstrap.Profile.CLOSED_V1 && branch == 0 && mask == 1) {
                for ((candidate, selectedProfile, selectedMask) in listOf(Triple(map - "agent", profile, 1), Triple(map, profile, 0), Triple(map, null, 1))) {
                    val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(null, candidate, selectedProfile, selectedMask, false, false) }
                    assertEquals(UnsupportedOperationException::class.java, failure.cause?.javaClass)
                }
            }
        }
            }
        }
    }
}
