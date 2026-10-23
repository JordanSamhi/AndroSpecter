package com.jordansamhi.androspecter.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A caller-owned deadline that invokes one callback on a daemon thread. Scheduling begins
 * immediately on creation. Use try-with-resources to disarm the callback when the operation
 * finishes; this timer neither owns nor waits for the operation being timed.
 */
public final class Deadline implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Deadline.class);
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> scheduled;
    private final AtomicBoolean pending = new AtomicBoolean(true);
    private volatile boolean expired;

    private Deadline(long delayNanos, Runnable action) {
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "androspecter-deadline");
            thread.setDaemon(true);
            return thread;
        });
        scheduled = scheduler.schedule(() -> {
            try {
                if (pending.compareAndSet(true, false)) {
                    expired = true;
                    action.run();
                }
            } catch (RuntimeException e) {
                LOG.warn("Deadline callback failed", e);
            } finally {
                scheduler.shutdown();
            }
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Schedules a callback after a non-negative duration. To request cancellation, pass
     * {@code source::cancel}. The callback should return promptly. Its runtime exceptions are
     * logged, and its scheduler is always shut down after invocation.
     * This does not guarantee that a cooperative operation stops at the deadline.
     * <pre>{@code
     * try (Deadline deadline = Deadline.after(Duration.ofSeconds(30), source::cancel)) {
     *     // Run the operation with source.token().
     * }
     * }</pre>
     *
     * @param delay duration before the callback; zero schedules it immediately
     * @param action caller-defined response to expiration
     * @return deadline to close when the operation finishes before expiration
     * @throws NullPointerException if {@code delay} or {@code action} is null
     * @throws IllegalArgumentException if the delay is negative or cannot be represented in nanoseconds
     */
    public static Deadline after(Duration delay, Runnable action) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(action, "action");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Deadline duration must not be negative");
        }
        final long nanos;
        try {
            nanos = delay.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Deadline duration is too large", e);
        }
        return new Deadline(nanos, action);
    }

    /**
     * Tests whether expiration won the race with closure and started invoking its callback.
     * The value remains true after callback completion or failure. It remains false when
     * closed before expiration and is safe to query from another thread.
     *
     * @return true if callback invocation has begun, not necessarily finished
     */
    public boolean isExpired() {
        return expired;
    }

    /**
     * Disarms a pending callback and shuts down the timer's scheduler. This method is
     * idempotent and safe to call from another thread. It neither interrupts nor waits for
     * an action already running, and does not reset {@link #isExpired()} after expiration.
     */
    @Override
    public void close() {
        pending.set(false);
        scheduled.cancel(false);
        scheduler.shutdown();
    }
}
