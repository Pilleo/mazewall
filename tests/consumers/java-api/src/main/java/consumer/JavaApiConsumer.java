package consumer;

import io.mazewall.enforcer.api.WorkerPolicyBuilder;
import io.mazewall.enforcer.api.RuntimeProfile;
import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.enforcer.api.Policy;
import io.mazewall.enforcer.api.PolicyScope;
import io.mazewall.enforcer.api.PolicyState;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import io.mazewall.enforcer.api.execution.InProcessDomain;
import io.mazewall.platform.syscall.Syscall;
import java.util.concurrent.Callable;

public final class JavaApiConsumer {
    private JavaApiConsumer() {}

    public static String callableExample(ManagedRuntime runtime, InProcessDomain domain) {
        return Mazewall.callContained(runtime, domain, () -> "contained");
    }

    public static void main(String[] args) {
        WorkerPolicyBuilder builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT);
        builder.base(Policy.NO_EXEC_HOTSPOT);
        builder.allowFsRead("/tmp/consumer-resource");
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy = builder.build();
        if (policy.getAllowedFsReadPaths().size() != 1
                || policy.isSyscallAllowed(Syscall.EXECVE)) {
            throw new AssertionError("Packaged Java builder lost requested restrictions");
        }
        WorkerPolicyBuilder mappings = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowMmapExec(true);
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> enabled = mappings.build();
        mappings.allowMmapExec(false);
        if (!enabled.getAllowMmapExec() || mappings.build().getAllowMmapExec()) {
            throw new AssertionError("Executable mapping setter lost false or mutated a built policy");
        }
        System.out.println("Packaged Java policy construction passed.");
    }
}
