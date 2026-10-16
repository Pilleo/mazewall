package consumer;

import io.mazewall.enforcer.api.ProcessPolicyBuilder;
import io.mazewall.enforcer.api.RuntimeProfile;

public final class ProcessFilesystem {
    public static void configure() {
        ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead("/input");
    }
}
