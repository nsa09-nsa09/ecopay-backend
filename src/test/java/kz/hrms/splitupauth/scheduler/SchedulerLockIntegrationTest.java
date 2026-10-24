package kz.hrms.splitupauth.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Block 6: a scheduled job must run on exactly ONE node even with multiple replicas. Two threads
 * (each taking its own pooled connection = two "nodes") contend for the same advisory lock; while
 * node A holds it inside the job, node B must skip rather than run the job a second time.
 */
class SchedulerLockIntegrationTest extends AbstractIntegrationTest {

  @Autowired SchedulerLock schedulerLock;

  @Test
  void jobRunsOnOnlyOneNode_whenTwoNodesContend() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch nodeAInside = new CountDownLatch(1);
    CountDownLatch releaseNodeA = new CountDownLatch(1);

    Thread nodeA =
        new Thread(
            () ->
                schedulerLock.runExclusive(
                    SchedulerLock.Key.TEST_ONLY,
                    () -> {
                      runs.incrementAndGet();
                      nodeAInside.countDown();
                      try {
                        releaseNodeA.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    nodeA.start();

    // Wait until node A is inside the critical section holding the lock.
    assertTrue(nodeAInside.await(5, TimeUnit.SECONDS), "node A should have acquired the lock");

    // Node B tries the same key while A holds it — it must skip (job not executed by B).
    schedulerLock.runExclusive(SchedulerLock.Key.TEST_ONLY, runs::incrementAndGet);
    assertEquals(1, runs.get(), "second node must NOT run the job while the first holds the lock");

    releaseNodeA.countDown();
    nodeA.join(5_000);
    assertEquals(1, runs.get(), "job must have run exactly once across both nodes");

    // After release, the lock is free again: a later run acquires it.
    schedulerLock.runExclusive(SchedulerLock.Key.TEST_ONLY, runs::incrementAndGet);
    assertEquals(2, runs.get(), "lock must be reusable once released");
  }
}
