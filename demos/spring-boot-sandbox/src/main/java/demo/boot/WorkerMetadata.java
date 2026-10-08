package demo.boot;

import io.mazewall.enforcer.api.Mazewall;
import java.util.Objects;

/** Actual body observation, not a new core ownership capability or kernel attestation. */
public record WorkerMetadata(Thread physicalThread, long threadId, String threadName,
                             boolean virtual, long domainGeneration) {
    static WorkerMetadata capture() {
        var evidence = Objects.requireNonNull(Mazewall.currentInvocationEvidence(), "Required owned body evidence");
        Thread thread = Thread.currentThread();
        if (thread.isVirtual()) throw new IllegalStateException("Protected body must use dedicated platform worker");
        return new WorkerMetadata(thread, thread.threadId(), thread.getName(), false, evidence.getDomainGeneration());
    }
}
