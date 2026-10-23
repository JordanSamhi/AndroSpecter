package com.jordansamhi.androspecter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class TimeOutTest {

    @Test
    public void testConstructorWithPositiveValue() {
        TimeOut timeout = new TimeOut(120);
        assertEquals(120, timeout.getTimeout());
    }

    @Test
    public void testConstructorWithZeroValue() {
        TimeOut timeout = new TimeOut(0);
        assertEquals(60, timeout.getTimeout()); // Default timeout is 60 seconds
    }

    @Test
    public void testCancel() {
        TimeOut timeout = new TimeOut(120);
        timeout.launch();
        timeout.cancel(); // Test to ensure no exceptions are thrown
    }

    @Test
    void schedulesInSecondsAndUsesDaemonThread() throws InterruptedException {
        CountDownLatch fired = new CountDownLatch(1);
        boolean[] daemon = new boolean[1];
        TimeOut timeout = new TimeOut(1, () -> {
            daemon[0] = Thread.currentThread().isDaemon();
            fired.countDown();
        });
        try {
            timeout.launch();
            assertTrue(fired.await(4, TimeUnit.SECONDS), "A one-second timeout must not wait a minute");
            assertTrue(daemon[0]);
        } finally {
            timeout.cancel();
        }
    }

    @Test
    void cancellationPreventsAction() throws InterruptedException {
        CountDownLatch fired = new CountDownLatch(1);
        TimeOut timeout = new TimeOut(1, fired::countDown);
        timeout.launch();
        timeout.cancel();
        assertFalse(fired.await(1200, TimeUnit.MILLISECONDS));
    }

    @Test
    void rejectsNegativeDurationsAndRelaunchAfterCancellation() {
        assertThrows(IllegalArgumentException.class, () -> new TimeOut(-1));
        TimeOut timeout = new TimeOut(1, () -> fail("Cancelled timer fired"));
        timeout.cancel();
        assertThrows(IllegalStateException.class, timeout::launch);
    }

    @Test
    void legacyTimeoutSignalsCancellationAndReturnsControlWithoutExiting() throws InterruptedException {
        CountDownLatch cancelled = new CountDownLatch(1);
        try (TimeOut timeout = new TimeOut(1)) {
            timeout.cancellationToken().onCancellation(cancelled::countDown);
            timeout.launch();
            assertTrue(cancelled.await(4, TimeUnit.SECONDS));
            assertTrue(timeout.cancellationToken().isCancellationRequested());
        }
    }
}
