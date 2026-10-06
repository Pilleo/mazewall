package consumer;

import io.mazewall.enforcer.api.JavaPolicyBuilder;
import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.enforcer.api.Policy;
import io.mazewall.enforcer.api.PolicyScope;
import io.mazewall.enforcer.api.PolicyState;
import io.mazewall.platform.syscall.Syscall;
import java.util.concurrent.Callable;

public final class JavaApiConsumer {
    private JavaApiConsumer() {}

    public static String callableExample(Policy<?, ?> policy) {
        return Mazewall.runContained(policy, (Callable<String>) () -> "contained");
    }

    public static void main(String[] args) {
        JavaPolicyBuilder builder = Mazewall.threadLocalBuilder();
        builder.base(Mazewall.noExec());
        builder.allowFsRead("/tmp/consumer-resource");
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy = builder.build();
        if (policy.getAllowedFsReadPaths().size() != 1
                || policy.isSyscallAllowed(Syscall.EXECVE)) {
            throw new AssertionError("Packaged Java builder lost requested restrictions");
        }
        JavaPolicyBuilder mappings = Mazewall.threadLocalBuilder().allowMmapExec(true);
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> enabled = mappings.build();
        mappings.allowMmapExec(false);
        if (!enabled.getAllowMmapExec() || mappings.build().getAllowMmapExec()) {
            throw new AssertionError("Executable mapping setter lost false or mutated a built policy");
        }
        System.out.println("Packaged Java policy construction passed.");
    }
}
