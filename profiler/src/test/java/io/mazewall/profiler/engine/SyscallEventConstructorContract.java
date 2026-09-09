package io.mazewall.profiler.engine;

import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.KVisibility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Uses the existing reflection runtime to exercise the public Kotlin construction contract. */
final class SyscallEventConstructorContract {
    private SyscallEventConstructorContract() {}

    static SyscallEvent<?> construct(Map<String, ?> values) throws ReflectiveOperationException {
        KClass<?> type = JvmClassMappingKt.getKotlinClass(SyscallEvent.class);
        assertEquals(KVisibility.PUBLIC, type.getVisibility());
        KFunction<?> constructor = (KFunction<?>) Class.forName("kotlin.reflect.full.KClasses")
            .getMethod("getPrimaryConstructor", KClass.class).invoke(null, type);
        assertNotNull(constructor);
        assertEquals(KVisibility.PUBLIC, constructor.getVisibility());
        List<KParameter> parameters = constructor.getParameters();
        assertEquals(List.of("tid", "syscallName", "args", "paths", "stackTrace"),
            parameters.stream().map(KParameter::getName).toList());
        assertEquals(List.of("paths", "stackTrace"),
            parameters.stream().filter(KParameter::isOptional).map(KParameter::getName).toList());
        assertEquals(List.of("io.mazewall.core.Tid", "kotlin.String", "kotlin.collections.List<kotlin.Long>",
            "kotlin.collections.List<kotlin.String>", "kotlin.collections.List<kotlin.String>?"),
            parameters.stream().map(p -> p.getType().toString()).toList());
        Map<KParameter, Object> arguments = new LinkedHashMap<>();
        for (KParameter parameter : parameters) {
            if (values.containsKey(parameter.getName())) arguments.put(parameter, values.get(parameter.getName()));
        }
        return (SyscallEvent<?>) constructor.callBy(arguments);
    }
}
