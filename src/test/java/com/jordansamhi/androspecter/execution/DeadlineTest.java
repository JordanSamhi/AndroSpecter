package com.jordansamhi.androspecter.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineTest {
    @Test
    void expirationInvokesCallerCancellationOnADaemonAndReleasesTheScheduler() throws InterruptedException {
        CancellationSource source = new CancellationSource();
        CountDownLatch notified = new CountDownLatch(1);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        source.token().onCancellation(() -> {
            callbackThread.set(Thread.currentThread());
            notified.countDown();
        });
        try (Deadline deadline = Deadline.after(Duration.ofMillis(20), source::cancel)) {
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertTrue(deadline.isExpired());
            assertTrue(source.token().isCancellationRequested());
            assertTrue(callbackThread.get().isDaemon());
            callbackThread.get().join(2000);
            assertFalse(callbackThread.get().isAlive());
        }
    }

    @Test
    void closingBeforeExpirationSuppressesTheCallback() throws InterruptedException {
        CountDownLatch invoked = new CountDownLatch(1);
        Deadline deadline = Deadline.after(Duration.ofMillis(150), invoked::countDown);
        deadline.close();
        deadline.close();
        assertFalse(invoked.await(250, TimeUnit.MILLISECONDS));
        assertFalse(deadline.isExpired());
    }

    @Test
    void callbackFailureStillReleasesItsScheduler() throws InterruptedException {
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CountDownLatch invoked = new CountDownLatch(1);
        try (Deadline ignored = Deadline.after(Duration.ZERO, () -> {
            callbackThread.set(Thread.currentThread());
            invoked.countDown();
            throw new IllegalStateException("Caller callback failed");
        })) {
            assertTrue(invoked.await(2, TimeUnit.SECONDS));
            callbackThread.get().join(2000);
            assertFalse(callbackThread.get().isAlive());
        }
    }

    @Test
    void invalidDurationIsRejectedBeforeAnyThreadStarts() {
        assertThrows(IllegalArgumentException.class,
                () -> Deadline.after(Duration.ofMillis(-1), () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> Deadline.after(Duration.ofSeconds(Long.MAX_VALUE), () -> { }));
    }
}
