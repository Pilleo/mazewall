package io.mazewall.portal.codegen

import io.mazewall.portal.codegen.testapi.SampleGreeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResolvedPortalContractTest {
    interface CollectionContract {
        fun transform(values: List<String>): Map<String, Int>
    }

    interface GenericCollectionContract<T> {
        fun transform(values: List<T>): Map<String, T>
    }

    interface StringCollectionContract : GenericCollectionContract<String>

    @Test
    fun `resolved contract retains the one effective method model used by source generation`() {
        val resolved = ResolvedPortalContract.compile(SampleGreeter::class.java)

        assertEquals(SampleGreeter::class.java, resolved.service)
        assertEquals(setOf("add", "greet"), resolved.methods.map { it.method.name }.toSet())
    }

    @Test
    fun `resolved contract retains parameterized list and map shapes`() {
        val method = ResolvedPortalContract.compile(CollectionContract::class.java).methods.single()

        assertIs<BoundaryTypes.Kind.ListT>(method.parameters.single().kind)
        assertIs<BoundaryTypes.Kind.MapT>(method.returnKind)
    }

    @Test
    fun `collection contract generates bounded collection codecs`() {
        val source = PortalStubGenerator.generate(CollectionContract::class.java).joinToString("\n") { it.toString() }

        kotlin.test.assertTrue(source.contains("collectionCount()"), source)
        kotlin.test.assertTrue(source.contains("flatMap"), source)
    }

    @Test
    fun `resolved inherited generic contract substitutes concrete boundary types`() {
        val method = ResolvedPortalContract.compile(StringCollectionContract::class.java).methods.single()

        val parameter = assertIs<BoundaryTypes.Kind.ListT>(method.parameters.single().kind)
        assertEquals(BoundaryTypes.Kind.StringT, parameter.element)
        val result = assertIs<BoundaryTypes.Kind.MapT>(method.returnKind)
        assertEquals(BoundaryTypes.Kind.StringT, result.key)
        assertEquals(BoundaryTypes.Kind.StringT, result.value)
    }
}
