package io.mazewall.portal.codegen

import io.mazewall.portal.codegen.testapi.SampleGreeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResolvedPortalContractTest {
    @Test
    fun `nullable list elements in DTOs are rejected during resolution`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.NullableListMemberDtoService::class.java)
        }
        kotlin.test.assertTrue(checkNotNull(ex.message).contains("parameter dto field values"))
    }

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
        kotlin.test.assertTrue(source.contains("writer.list("), source)
        kotlin.test.assertTrue(source.contains("writer.map("), source)
        kotlin.test.assertFalse(source.contains("flatMap"), source)
        kotlin.test.assertFalse(source.contains("PortalCodec.concat"), source)
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

    @Test
    fun `abstract DTO is rejected during resolution with field path`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.AbstractDtoService::class.java)
        }
        val message = checkNotNull(ex.message)
        kotlin.test.assertTrue(message.contains("AbstractDtoService.echo parameter dto"))
        kotlin.test.assertTrue(message.contains("AbstractDtoService.echo.dto"))
        kotlin.test.assertTrue(message.contains("abstract DTO io.mazewall.portal.codegen.testapi.AbstractDto is not constructible"))
    }

    @Test
    fun `nullable DTO member is rejected during resolution with field path`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.NullableMemberDtoService::class.java)
        }
        val message = checkNotNull(ex.message)
        kotlin.test.assertTrue(message.contains("NullableMemberDtoService.echo parameter dto field name"))
        kotlin.test.assertTrue(message.contains("NullableMemberDtoService.echo.dto.name"))
        kotlin.test.assertTrue(message.contains("nullable DTO components are not supported"))
    }

    @Test
    fun `non static inner class DTO is rejected during resolution with field path`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.NonStaticMemberDtoService::class.java)
        }
        val message = checkNotNull(ex.message)
        kotlin.test.assertTrue(message.contains("NonStaticMemberDtoService.echo parameter dto"))
        kotlin.test.assertTrue(message.contains("member type io.mazewall.portal.codegen.testapi.EnclosingScope\$NonStaticMemberDto must be static"))
    }

    @Test
    fun `non public enclosing class DTO is rejected during resolution with field path`() {
        val serviceClass = Class.forName("io.mazewall.portal.codegen.testapi.PackagePrivateOuter\$EnclosedService")
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ResolvedPortalContract.compile(serviceClass)
        }
        val message = checkNotNull(ex.message)
        kotlin.test.assertTrue(message.contains("EnclosedService.echo parameter dto"))
        kotlin.test.assertTrue(
            message.contains(
                "type io.mazewall.portal.codegen.testapi.PackagePrivateOuter\$PublicInner " +
                    "enclosing class io.mazewall.portal.codegen.testapi.PackagePrivateOuter must be public",
            ),
        )
    }

    @Test
    fun `inaccessible constructor DTO is rejected during resolution with field path`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.PrivateConstructorPojoService::class.java)
        }
        val message = checkNotNull(ex.message)
        kotlin.test.assertTrue(message.contains("PrivateConstructorPojoService.echo parameter dto"))
        kotlin.test.assertTrue(message.contains("need a java record or a single public all-args constructor"))
    }

    @Test
    fun `record with generic list component retains element type`() {
        val method = ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.NamesRecordService::class.java).methods.single()
        val recordKind = assertIs<BoundaryTypes.Kind.RecordT>(method.parameters.single().kind)
        val component = recordKind.components.single()
        val listKind = assertIs<BoundaryTypes.Kind.ListT>(component.kind)
        assertEquals(BoundaryTypes.Kind.StringT, listKind.element)
    }

    @Test
    fun `pojo with generic list component retains element type`() {
        val method = ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.NamesPojoService::class.java).methods.single()
        val recordKind = assertIs<BoundaryTypes.Kind.RecordT>(method.parameters.single().kind)
        val component = recordKind.components.single()
        val listKind = assertIs<BoundaryTypes.Kind.ListT>(component.kind)
        assertEquals(BoundaryTypes.Kind.StringT, listKind.element)
    }

    @Test
    fun `kotlin generic dto retains element type`() {
        val method = ResolvedPortalContract.compile(io.mazewall.portal.codegen.testapi.KotlinGenericDtoService::class.java).methods.single()
        val recordKind = assertIs<BoundaryTypes.Kind.RecordT>(method.parameters.single().kind)
        val component = recordKind.components.single()
        val listKind = assertIs<BoundaryTypes.Kind.ListT>(component.kind)
        assertEquals(BoundaryTypes.Kind.StringT, listKind.element)
    }
}
