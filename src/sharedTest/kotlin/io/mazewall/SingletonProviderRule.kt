package io.mazewall.enforcer.api

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.JavaParameterizedType
import com.tngtech.archunit.core.domain.JavaType
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import io.mazewall.platform.native.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*

fun singletonProviderRule() =
    classes().should(object : ArchCondition<JavaClass>("keep singleton provider seams immutable") {
    override fun check(
        item: JavaClass,
        events: ConditionEvents,
    ) {
        val singleton = item.fields.any { field ->
            JavaModifier.STATIC in field.modifiers &&
                JavaModifier.FINAL in field.modifiers &&
                field.rawType == item
        } ||
            item.enclosingClass.orElse(null)?.fields?.any { field ->
            JavaModifier.STATIC in field.modifiers && field.rawType == item
        } == true

        // Existing compatibility APIs; new fields/setters on these facades still fail.
        val compatibleFields = setOf("io.mazewall.enforcer.api.Platform.provider", "io.mazewall.platform.native.LinuxNative.engine")
        val compatibleSetterPrefixes = setOf(
            "io.mazewall.enforcer.api.Platform.setProvider",
            "io.mazewall.platform.native.LinuxNative.setEngine",
        )
        item.fields
            .filter { field ->
            (singleton || JavaModifier.STATIC in field.modifiers) && JavaModifier.FINAL !in field.modifiers && isProvider(field.type) && field.fullName !in compatibleFields
        }.forEach { field ->
            events.add(SimpleConditionEvent.violated(field, "Mutable singleton provider field ${field.fullName}"))
        }
        item.methods
            .filter { method ->
            (singleton || JavaModifier.STATIC in method.modifiers) &&
                method.name.startsWith("set") &&
                method.parameterTypes.any(::isProvider) &&
                compatibleSetterPrefixes.none(method.fullName::startsWith)
        }.forEach { method ->
            events.add(SimpleConditionEvent.violated(method, "Mutable singleton provider setter ${method.fullName}"))
        }
    }

    private fun isProvider(type: JavaType): Boolean {
        val raw = type.toErasure()
        val producesValue = (type as? JavaParameterizedType)
            ?.actualTypeArguments
            ?.lastOrNull()
            ?.toErasure()
            ?.name != "kotlin.Unit"
        return raw.simpleName.endsWith("Provider") ||
            raw.isAssignableTo(NativeEngine::class.java) ||
            (raw.name.startsWith("kotlin.jvm.functions.Function") && producesValue)
    }
})
