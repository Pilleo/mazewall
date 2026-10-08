package io.mazewall.execution;

import io.mazewall.enforcer.api.RuntimeProfile;
import io.mazewall.enforcer.api.WorkerPolicyBuilder;
import io.mazewall.enforcer.api.execution.PreparedOperationResources;
import java.nio.file.Path;
import java.util.Set;

/** A disposable JVM probe: the selected class really belongs to its system loader. */
public final class ReviewSystemLoaderProbe {
    public static void main(String[] args) throws Exception {
        Class<?> selected = Class.forName("fixture.Operation", true, ClassLoader.getSystemClassLoader());
        if (selected.getClassLoader() != ClassLoader.getSystemClassLoader()) throw new AssertionError("Wrong fixture loader");
        int actual = (Integer) selected.getMethod("call").invoke(selected.getConstructor().newInstance());
        boolean custom = args[0].equals("custom");
        if (actual != (custom ? 99 : 42)) throw new AssertionError("Counterfeit fixture did not define different bytes");
        var policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead(Path.of(System.getProperty("task5.original.jar"))).build();
        try (var resources = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, Set.of())) {
            if (custom) throw new AssertionError("Custom system classloader accepted counterfeit defined bytes");
            resources.validate();
        } catch (IllegalArgumentException rejected) {
            if (!custom) throw rejected;
        }
    }
}
