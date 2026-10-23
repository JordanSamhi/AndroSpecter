package com.jordansamhi.androspecter.execution;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CancellationSourceTest {
    @Test
    void cancellationIsIdempotentAndLateRegistrationsAreInvokedImmediately() {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        source.token().onCancellation(calls::incrementAndGet);
        assertFalse(source.token().isCancellationRequested());
        assertTrue(source.cancel());
        assertFalse(source.cancel());
        source.token().onCancellation(calls::incrementAndGet);
        assertEquals(2, calls.get());
        assertTrue(source.token().isCancellationRequested());
        assertThrows(CancellationException.class, source.token()::throwIfCancellationRequested);
    }

    @Test
    void closedRegistrationIsNotInvokedAndFailuresDoNotSuppressOtherCallbacks() {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        source.token().onCancellation(() -> fail("Removed registration fired")).close();
        source.token().onCancellation(() -> { throw new IllegalStateException("Caller callback failed"); });
        source.token().onCancellation(calls::incrementAndGet);
        assertDoesNotThrow(source::cancel);
        assertEquals(1, calls.get());
    }

    @Test
    void callbacksCanRegisterOtherCallbacksWithoutHoldingTheSourceLock() {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        source.token().onCancellation(() -> source.token().onCancellation(calls::incrementAndGet));
        source.cancel();
        assertEquals(1, calls.get());
    }

    @Test
    void callerInterruptionIsPreservedByTheCancellationCheckpoint() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, CancellationToken.none()::throwIfCancellationRequested);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void crossThreadCancellationWakesRegisteredCaller() throws InterruptedException {
        CountDownLatch notified = new CountDownLatch(1);
        CancellationToken token;
        try (CancellationSource source = new CancellationSource()) {
            token = source.token();
            token.onCancellation(notified::countDown);
            Thread cancelling = new Thread(source::cancel);
            cancelling.start();
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            cancelling.join(2000);
            assertFalse(cancelling.isAlive());
            assertTrue(token.isCancellationRequested());
        }
        assertTrue(token.isCancellationRequested());
    }

    @Test
    void closingTheCallerScopeRequestsCancellation() {
        CancellationToken token;
        try (CancellationSource source = new CancellationSource()) {
            token = source.token();
            assertFalse(token.isCancellationRequested());
        }
        assertTrue(token.isCancellationRequested());
    }
}
