package io.mazewall.ffi.networking;

import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Exercises kotlin-reflect already present at test runtime, without a new dependency. */
final class NativeDescriptorConstructorContract {
    private NativeDescriptorConstructorContract() {}

    static NativeDescriptorInputStream construct(Map<String, ?> values) throws ReflectiveOperationException {
        KClass<?> type = JvmClassMappingKt.getKotlinClass(NativeDescriptorInputStream.class);
        KFunction<?> constructor = (KFunction<?>) Class.forName("kotlin.reflect.full.KClasses")
            .getMethod("getPrimaryConstructor", KClass.class).invoke(null, type);
        assertNotNull(constructor);
        List<KParameter> parameters = constructor.getParameters();
        assertEquals(List.of("socketFd", "arena", "description"), parameters.stream().map(KParameter::getName).toList());
        assertEquals(List.of("description"), parameters.stream().filter(KParameter::isOptional).map(KParameter::getName).toList());
        assertEquals(List.of("io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>",
            "io.mazewall.ffi.memory.NativeArena", "kotlin.String"), parameters.stream().map(p -> p.getType().toString()).toList());
        Map<KParameter, Object> arguments = new LinkedHashMap<>();
        for (KParameter parameter : parameters) {
            if (values.containsKey(parameter.getName())) arguments.put(parameter, values.get(parameter.getName()));
        }
        return (NativeDescriptorInputStream) constructor.callBy(arguments);
    }
}
