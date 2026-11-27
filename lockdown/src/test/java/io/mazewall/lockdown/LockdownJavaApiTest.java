package io.mazewall.lockdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

class LockdownJavaApiTest {
    @Test
    void javaCallersCanConstructPoliciesSafelyAndObserveEncapsulatedSurface() {
        ProcessPolicy hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT);
        ProcessPolicy nativeImage = ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE);
        assertNotNull(hotspot);
        assertNotEquals(hotspot, nativeImage);

        long nonSyntheticInstallationCtors = Arrays.stream(ProcessInstallation.class.getDeclaredConstructors())
            .filter(c -> Modifier.isPublic(c.getModifiers()) && !c.isSynthetic())
            .count();
        assertEquals(0L, nonSyntheticInstallationCtors);

        long nonSyntheticPolicyCtors = Arrays.stream(ProcessPolicy.class.getDeclaredConstructors())
            .filter(c -> Modifier.isPublic(c.getModifiers()) && !c.isSynthetic())
            .count();
        assertEquals(0L, nonSyntheticPolicyCtors);

        List<Class<?>> apiTypes = List.of(
            Mazewall.class,
            ProcessPolicy.class,
            ProcessInstallation.class,
            RuntimeProfile.class,
            LockdownPreset.class,
            LockdownSyscall.class,
            MazewallInstallationException.class
        );
        for (Class<?> apiType : apiTypes) {
            for (Constructor<?> ctor : apiType.getDeclaredConstructors()) {
                if (Modifier.isPublic(ctor.getModifiers()) && !ctor.isSynthetic()) {
                    Arrays.stream(ctor.getParameterTypes()).forEach(LockdownJavaApiTest::assertAllowedApiType);
                }
            }
            for (Method method : apiType.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                    assertFalse(method.getName().contains("$"), "Mangled method exposed: " + method);
                    assertAllowedApiType(method.getReturnType());
                    Arrays.stream(method.getParameterTypes()).forEach(LockdownJavaApiTest::assertAllowedApiType);
                }
            }
        }
    }

    @Test
    void javaCallersCanSelectOpenSearchAndElasticsearchPresetsAndCustomizeSyscalls() {
        ProcessPolicy elastic = ProcessPolicy.elasticsearch();
        ProcessPolicy openSearch = ProcessPolicy.openSearch(RuntimeProfile.NATIVE_IMAGE);
        ProcessPolicy strict = ProcessPolicy.fromPreset(LockdownPreset.MAZEWALL_STRICT);

        assertEquals(RuntimeProfile.HOTSPOT_JIT, elastic.runtimeProfile());
        assertEquals(RuntimeProfile.NATIVE_IMAGE, openSearch.runtimeProfile());
        assertEquals(4, elastic.blockedSyscalls().size());
        assertEquals(8, openSearch.blockedSyscalls().size());
        assertEquals(11, strict.blockedSyscalls().size());
        assertEquals("pidfd_getfd", LockdownSyscall.PIDFD_GETFD.syscallName());

        ProcessPolicy customized = elastic
            .block(LockdownSyscall.PIDFD_GETFD, LockdownSyscall.BPF)
            .unblock(LockdownSyscall.VFORK)
            .block(List.of(LockdownSyscall.UNSHARE))
            .unblock(List.of(LockdownSyscall.BPF));

        assertEquals(Set.of(
            LockdownSyscall.EXECVE,
            LockdownSyscall.EXECVEAT,
            LockdownSyscall.FORK,
            LockdownSyscall.PIDFD_GETFD,
            LockdownSyscall.UNSHARE
        ), customized.blockedSyscalls());

        assertThrows(UnsupportedOperationException.class, () -> customized.blockedSyscalls().add(LockdownSyscall.BPF));
        assertThrows(NullPointerException.class, () -> ProcessPolicy.fromPreset(null));
        assertThrows(NullPointerException.class, () -> ProcessPolicy.elasticsearch(null));
        assertThrows(NullPointerException.class, () -> elastic.block((LockdownSyscall) null));
        assertThrows(NullPointerException.class, () -> elastic.block(Arrays.asList(LockdownSyscall.BPF, null)));
        assertThrows(NullPointerException.class, () -> elastic.unblock((LockdownSyscall) null));
        assertThrows(NullPointerException.class, () -> elastic.unblock(Arrays.asList(LockdownSyscall.FORK, null)));
    }

    @Test
    void nullArgumentsFailWithCleanPublicNullPointerException() {
        ProcessPolicy hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT);
        NullPointerException varargNpe = assertThrows(
            NullPointerException.class,
            () -> Mazewall.installOnProcess(hotspot, (ProcessPolicy) null)
        );
        assertFalse(varargNpe.getMessage().contains("io_mazewall_mazewall_lockdown"));
        assertTrue(varargNpe.getMessage().contains("index 0"));

        assertThrows(NullPointerException.class, () -> Mazewall.installOnProcess(null));
    }

    @Test
    void conflictingRuntimeProfilesAreRejectedCleanly() {
        ProcessPolicy hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT);
        ProcessPolicy nativeImage = ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE);
        MazewallInstallationException ex = assertThrows(
            MazewallInstallationException.class,
            () -> Mazewall.installOnProcess(hotspot, nativeImage)
        );
        assertTrue(ex.getMessage().contains("Conflicting RuntimeProfile"));
    }

    @Test
    void virtualThreadInvocationThrowsMazewallInstallationExceptionToJavaCallers() throws Exception {
        ProcessPolicy policy = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionException thrown = assertThrows(ExecutionException.class, () ->
                executor.submit(() -> Mazewall.installOnProcess(policy)).get()
            );
            assertEquals(MazewallInstallationException.class, thrown.getCause().getClass());
            String message = thrown.getCause().getMessage();
            assertNotNull(message);
            assertTrue(message.contains("platform thread"));
            assertFalse(message.contains("ManagedRuntime"));
            assertFalse(message.contains("portal worker"));
        }
    }

    private static void assertAllowedApiType(Class<?> rawType) {
        Class<?> base = rawType;
        while (base.isArray()) {
            base = base.getComponentType();
        }
        if (base.isPrimitive()) {
            return;
        }
        String name = base.getName();
        boolean allowed = (name.startsWith("io.mazewall.lockdown.") && !name.startsWith("io.mazewall.lockdown.internal.")) ||
            name.startsWith("java.lang.") ||
            name.equals("java.util.Set") ||
            name.equals("java.util.Collection") ||
            name.equals("kotlin.enums.EnumEntries");
        assertTrue(allowed, "Internal or leaked type in public JVM signature: " + name);
    }
}
