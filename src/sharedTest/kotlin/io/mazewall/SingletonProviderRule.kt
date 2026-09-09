package io.mazewall

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.JavaParameterizedType
import com.tngtech.archunit.core.domain.JavaType
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

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
        val compatibleFields = setOf("io.mazewall.Platform.provider", "io.mazewall.LinuxNative.engine")
        val compatibleSetters = setOf(
            "io.mazewall.Platform.setProvider(io.mazewall.PlatformProvider)",
            "io.mazewall.LinuxNative.setEngine(io.mazewall.NativeEngine)",
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
                method.fullName !in compatibleSetters
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
