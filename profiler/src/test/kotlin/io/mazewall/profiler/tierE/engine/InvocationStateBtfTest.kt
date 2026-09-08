package io.mazewall.profiler.tierE.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvocationStateBtfTest {
    @Test
    fun `loads the build generated invocation state btf resource`() {
        val btf = InvocationStateBtf.load()

        assertTrue(btf.size > 24, "BTF must contain a header and at least one type")
    }

    @Test
    fun `finds the generated invocation state structure by name`() {
        val type = InvocationStateBtf.findStructTypeId(InvocationStateBtf.load(), "mazewall_invocation_state")

        assertTrue(type > 0)
        assertEquals(type, InvocationStateBtf.invocationStateTypeId())
    }

    @Test
    fun `finds the signed int key type required by task storage`() {
        assertTrue(InvocationStateBtf.taskStorageKeyTypeId() > 0)
    }
}
