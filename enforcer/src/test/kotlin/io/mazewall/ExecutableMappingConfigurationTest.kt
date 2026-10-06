package io.mazewall.enforcer.api

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutableMappingConfigurationTest {
    @Test
    fun `false removes an earlier executable mapping grant without changing built policies`() {
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowMmapExec(true)
        val enabled = builder.build()

        builder.allowMmapExec(false)
        val disabled = builder.build()

        assertTrue(enabled.allowMmapExec)
        assertFalse(disabled.allowMmapExec)
        assertFalse(disabled.argumentRules.allowExecutableMappings)
    }

    @Test
    fun `false removes a grant selected by the runtime profile`() {
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)

        builder.allowMmapExec(false)

        assertFalse(builder.build().allowMmapExec)
    }

    @Test
    fun `runtime selection after an explicit false is the final preinstall setting`() {
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowMmapExec(true).allowMmapExec(false)
        val disabled = builder.build()

        builder.forRuntime(RuntimeProfile.HOTSPOT_JIT)

        assertFalse(disabled.allowMmapExec)
        assertTrue(builder.build().allowMmapExec)
    }
}
