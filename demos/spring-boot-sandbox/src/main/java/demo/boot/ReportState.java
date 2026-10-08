package demo.boot;

import java.util.concurrent.CountDownLatch;

/** Visible application heap effects and test observation; never treated as rollbackable state. */
final class ReportState {
    volatile WorkerMetadata worker;
    volatile int bodyEntries;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch cancellationSeen = new CountDownLatch(1);
    final CountDownLatch releaseCleanup = new CountDownLatch(1);
    final CountDownLatch bodyEnded = new CountDownLatch(1);
}
