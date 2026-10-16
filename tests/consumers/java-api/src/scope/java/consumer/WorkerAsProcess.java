package consumer;

import io.mazewall.enforcer.api.Policy;
import io.mazewall.enforcer.api.PolicyScope;
import io.mazewall.enforcer.api.PolicyState;
import io.mazewall.enforcer.api.RuntimeProfile;
import io.mazewall.enforcer.api.WorkerPolicyBuilder;

public final class WorkerAsProcess {
    public static Policy<PolicyScope.ProcessWideSafe, PolicyState.Uncompiled> configure() {
        return WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead("/input").build();
    }
}
