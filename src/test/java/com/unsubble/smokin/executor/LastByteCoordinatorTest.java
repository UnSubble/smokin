package com.unsubble.smokin.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class LastByteCoordinatorTest {

    private static final int TIMEOUT_SECONDS = 5;

    @Test
    void constructorRejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> new LastByteCoordinator(0));
    }

    @Test
    void constructorRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new LastByteCoordinator(-1));
        assertThrows(IllegalArgumentException.class, () -> new LastByteCoordinator(Integer.MIN_VALUE));
    }

    @Test
    void constructorAcceptsPositive() {
        assertDoesNotThrow(() -> new LastByteCoordinator(1));
        assertDoesNotThrow(() -> new LastByteCoordinator(100));
    }

    @Test
    void initialStateIsConsistent() {
        LastByteCoordinator c = new LastByteCoordinator(3);
        assertEquals(3, c.getTotalParties());
        assertEquals(0, c.getArrived());
        assertFalse(c.isBroken());
        assertNull(c.getFailureCause());
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void singlePartyPassesImmediately() throws IOException {
        LastByteCoordinator c = new LastByteCoordinator(1);

        c.await();
        assertEquals(1, c.getArrived());
        assertFalse(c.isBroken());
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void allPartiesPassAfterLastArrives() throws Exception {
        int parties = 4;
        LastByteCoordinator c = new LastByteCoordinator(parties);

        CountDownLatch allPassed = new CountDownLatch(parties);
        AtomicInteger errors = new AtomicInteger(0);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < parties; i++) {
            Thread t = new Thread(() -> {
                try {
                    c.await();
                    allPassed.countDown();
                } catch (IOException e) {
                    errors.incrementAndGet();
                }
            });
            t.setDaemon(true);
            threads.add(t);
        }
        threads.forEach(Thread::start);

        assertTrue(allPassed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "All parties should have passed the barrier");
        assertEquals(0, errors.get(), "No thread should have seen an IOException");
        assertEquals(parties, c.getArrived());
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void arrivedCountIncreasesCorrectly() throws Exception {
        int parties = 3;
        LastByteCoordinator c = new LastByteCoordinator(parties);

        Semaphore threadReady = new Semaphore(0);
        CountDownLatch done = new CountDownLatch(parties);

        for (int i = 0; i < parties; i++) {
            new Thread(() -> {
                try {
                    threadReady.release(); // signal we are about to enter await()
                    c.await();
                    done.countDown();
                } catch (IOException e) {
                    done.countDown();
                }
            }).start();
        }

        assertTrue(threadReady.tryAcquire(parties, TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(parties, c.getArrived());
    }


    @Test
    @Timeout(TIMEOUT_SECONDS)
    void firstPartiesBlockUntilLastArrives() throws Exception {
        int parties = 3;
        LastByteCoordinator c = new LastByteCoordinator(parties);

        CountDownLatch firstTwoPassed = new CountDownLatch(parties - 1); // the waiting ones
        CountDownLatch allPassed = new CountDownLatch(parties);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < parties - 1; i++) {
            new Thread(() -> {
                try {
                    c.await();
                    firstTwoPassed.countDown();
                    allPassed.countDown();
                } catch (IOException e) {
                    errors.incrementAndGet();
                }
            }).start();
        }

        Thread.sleep(100); // intentional: we need at least a little time for threads to enter wait
        assertEquals(parties - 1, firstTwoPassed.getCount(),
                "Waiting parties must not pass until the last party arrives");

        new Thread(() -> {
            try {
                c.await();
                allPassed.countDown();
            } catch (IOException e) {
                errors.incrementAndGet();
            }
        }).start();

        assertTrue(allPassed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "All parties should pass after last arrival");
        assertEquals(0, errors.get());
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void abortWakesAllWaitingThreads() throws Exception {
        int parties = 4;
        LastByteCoordinator c = new LastByteCoordinator(parties);

        CountDownLatch failedCount = new CountDownLatch(parties - 1);
        AtomicInteger ioExceptions = new AtomicInteger(0);

        for (int i = 0; i < parties - 1; i++) {
            new Thread(() -> {
                try {
                    c.await();
                } catch (IOException e) {
                    ioExceptions.incrementAndGet();
                    failedCount.countDown();
                }
            }).start();
        }

        Thread.sleep(80); // let them block

        RuntimeException cause = new RuntimeException("test abort");
        c.abort(cause);

        assertTrue(failedCount.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "All waiting threads should have been released by abort()");
        assertEquals(parties - 1, ioExceptions.get());
        assertTrue(c.isBroken());
        assertSame(cause, c.getFailureCause());
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void awaitAfterAbortThrowsImmediately() {
        LastByteCoordinator c = new LastByteCoordinator(2);
        c.abort(new RuntimeException("pre-broken"));

        assertThrows(IOException.class, c::await);
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void firstFailureCauseIsPreserved() {
        LastByteCoordinator c = new LastByteCoordinator(2);

        Throwable first = new RuntimeException("first");
        Throwable second = new RuntimeException("second");

        c.abort(first);
        c.abort(second); // must NOT overwrite

        assertSame(first, c.getFailureCause(),
                "First failure cause must not be overwritten by subsequent abort() calls");
    }

    @Test
    void abortWithNullCauseIsAccepted() {
        LastByteCoordinator c = new LastByteCoordinator(2);
        assertDoesNotThrow(() -> c.abort(null));
        assertTrue(c.isBroken());
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void interruptionMarksBrokenAndReleasesOtherWaiters() throws Exception {
        int parties = 3;
        LastByteCoordinator c = new LastByteCoordinator(parties);

        CountDownLatch allWaiting = new CountDownLatch(parties - 1);
        CountDownLatch othersDone = new CountDownLatch(parties - 2); // 1 other thread
        AtomicInteger ioExceptions = new AtomicInteger(0);

        Thread victim = new Thread(() -> {
            try {
                allWaiting.countDown(); // signal: about to wait
                c.await();
            } catch (IOException e) {
                ioExceptions.incrementAndGet();
            }
        });
        victim.setDaemon(true);

        for (int i = 0; i < parties - 2; i++) {
            new Thread(() -> {
                try {
                    allWaiting.countDown(); // signal: about to wait
                    c.await();
                } catch (IOException e) {
                    ioExceptions.incrementAndGet();
                    othersDone.countDown();
                }
            }).start();
        }

        victim.start();

        assertTrue(allWaiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "All threads must signal readiness before interrupt");
        Thread.sleep(100);

        victim.interrupt();

        victim.join(TIMEOUT_SECONDS * 1000L);
        assertFalse(victim.isAlive(), "Interrupted thread must not be alive (deadlock check)");

        assertTrue(c.isBroken(), "Interruption must mark the coordinator as broken");

        assertTrue(othersDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Other waiting threads must be released after interruption");

        assertTrue(ioExceptions.get() >= 1,
                "At least one thread must have received an IOException");
    }


    @Test
    @Timeout(TIMEOUT_SECONDS)
    void interruptedThreadHasInterruptFlagSet() throws Exception {
        LastByteCoordinator c = new LastByteCoordinator(2);

        AtomicReference<Boolean> wasInterrupted = new AtomicReference<>(null);
        CountDownLatch done = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            try {
                c.await();
            } catch (IOException e) {
                wasInterrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                done.countDown();
            }
        });
        t.setDaemon(true);
        t.start();

        Thread.sleep(50);
        t.interrupt();

        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(wasInterrupted.get(),
                "Thread.currentThread().isInterrupted() must be true after interruption");
    }

    @Test
    @Timeout(30)
    void noRaceConditionsUnderHighConcurrency() throws Exception {
        int iterations = 200;
        int parties = 5;

        for (int iter = 0; iter < iterations; iter++) {
            LastByteCoordinator c = new LastByteCoordinator(parties);
            CountDownLatch done = new CountDownLatch(parties);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < parties; i++) {
                new Thread(() -> {
                    try {
                        c.await();
                        done.countDown();
                    } catch (IOException e) {
                        errors.incrementAndGet();
                        done.countDown();
                    }
                }).start();
            }

            assertTrue(done.await(5, TimeUnit.SECONDS),
                    "Iteration " + iter + " timed out - possible deadlock or race condition");
            assertEquals(0, errors.get(), "Unexpected IOException in iteration " + iter);
            assertEquals(parties, c.getArrived());
        }
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void reuseAfterCompletionDoesNotDeadlock() throws Exception {
        LastByteCoordinator c = new LastByteCoordinator(1);
        c.await(); // completes normally

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                c.await();
            } catch (IOException ignored) {}
        });
        assertDoesNotThrow(() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Re-use after completion must not deadlock");
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void abortExceptionIncludesCauseMessage() {
        LastByteCoordinator c = new LastByteCoordinator(2);
        c.abort(new RuntimeException("connection refused"));

        IOException ex = assertThrows(IOException.class, c::await);
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("connection refused"),
                "IOException message should include the cause's message");
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void abortExceptionHasCauseAsChainedCause() {
        LastByteCoordinator c = new LastByteCoordinator(2);
        RuntimeException root = new RuntimeException("root cause");
        c.abort(root);

        IOException ex = assertThrows(IOException.class, c::await);
        assertSame(root, ex.getCause());
    }
}
