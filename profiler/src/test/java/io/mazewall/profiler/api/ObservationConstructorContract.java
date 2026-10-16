package io.mazewall.profiler.api;

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

/** Exercises the existing kotlin-reflect test runtime without adding a compile dependency. */
public final class ObservationConstructorContract {
    private static final Map<String, String> TYPES = Map.of(
        "correlation", "io.mazewall.profiler.api.ObservationCorrelation",
        "source", "io.mazewall.profiler.api.ObservationSource",
        "name", "kotlin.String",
        "args", "kotlin.collections.List<kotlin.Long>",
        "paths", "kotlin.collections.List<kotlin.String>",
        "openFlags", "kotlin.Long?",
        "contextId", "kotlin.Long?",
        "openFlagsEvidence", "io.mazewall.profiler.api.OpenFlagsEvidence?",
        "opcode", "kotlin.String",
        "endpoint", "io.mazewall.profiler.api.NetworkEndpoint"
    );

    private ObservationConstructorContract() {}

    public static <T> T construct(Class<T> javaType, List<String> names, List<String> optional, Map<String, ?> values)
            throws ReflectiveOperationException {
        KClass<T> type = JvmClassMappingKt.getKotlinClass(javaType);
        KFunction<?> constructor = (KFunction<?>) Class.forName("kotlin.reflect.full.KClasses")
            .getMethod("getPrimaryConstructor", KClass.class).invoke(null, type);
        assertNotNull(constructor);
        assertEquals(KVisibility.PUBLIC, constructor.getVisibility());
        List<KParameter> parameters = constructor.getParameters();
        assertEquals(names, parameters.stream().map(KParameter::getName).toList());
        assertEquals(optional, parameters.stream().filter(KParameter::isOptional).map(KParameter::getName).toList());
        assertEquals(names.stream().map(TYPES::get).toList(), parameters.stream().map(p -> p.getType().toString()).toList());
        Map<KParameter, Object> arguments = new LinkedHashMap<>();
        for (KParameter parameter : parameters) {
            if (values.containsKey(parameter.getName())) arguments.put(parameter, values.get(parameter.getName()));
        }
        return javaType.cast(constructor.callBy(arguments));
    }
}
