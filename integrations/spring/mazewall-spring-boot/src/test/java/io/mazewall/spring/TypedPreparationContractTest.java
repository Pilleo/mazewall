package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.ReviewedOperationArtifact;
import io.mazewall.enforcer.api.execution.DomainLimits;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import io.mazewall.enforcer.api.execution.PreparedOperationResources;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Public adapter contract: production must have a genuine owner, not metadata-only preparation. */
class TypedPreparationContractTest {
    @Test void genuineOwnerIsRequiredByProductionConstructor() throws Exception {
        assertNotNull(DomainLifecycle.class.getConstructor(ManagedRuntime.class, ReviewedOperationArtifact.class,
                DomainLimits.class, PreparedOperationResources.class, Duration.class));
        assertThrows(NoSuchMethodException.class, () -> DomainLifecycle.class.getConstructor(ManagedRuntime.class,
                ReviewedOperationArtifact.class, DomainLimits.class, Duration.class));
    }
}
