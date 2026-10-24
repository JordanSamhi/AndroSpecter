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
 * finishes; this timer neither owns nor waits for the operation being timed. Each
 * instance owns one scheduler. Its callback may start before {@link #after(Duration, Runnable)} returns,
 * particularly for a zero delay. Closing and querying expiration are thread-safe.
 * This utility does not access Soot and must not close an owner-thread analysis from
 * its scheduler thread; use the callback to signal a {@link CancellationSource} instead.
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
     * logged, and its scheduler is shut down when the invocation finishes, even if it fails.
     * Callback failures are not thrown on the thread calling this factory. Scheduling begins
     * immediately and may execute later than the requested delay; this is not a hard bound
     * on either callback start time or the duration of a cooperative operation.
     * <pre>{@code
     * try (CancellationSource source = new CancellationSource();
     *      Deadline deadline = Deadline.after(java.time.Duration.ofSeconds(30), source::cancel)) {
     *     // Run the operation with source.token().
     * }
     * }</pre>
     *
     * @param delay non-null nonnegative scheduling delay; zero makes the callback eligible immediately
     * @param action non-null caller-defined response, run at most once on the timer's daemon thread
     * @return caller-owned deadline to close when the timed operation finishes, even if already expired
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
     * Tests whether the scheduler has claimed expiration and set its expiration flag.
     * The flag is set just before invoking the action, so true does not prove that the action
     * has entered or completed, nor that cancellation reached the timed operation. It remains
     * true after action completion/failure and false if closure disarmed expiration first.
     * This method is safe to query from another thread.
     *
     * @return the currently observed expiration flag, independent of operation completion
     */
    public boolean isExpired() {
        return expired;
    }

    /**
     * Disarms a pending callback and shuts down the timer's scheduler. This method is
     * idempotent and safe to call from another thread. It neither interrupts nor waits for
     * an action whose invocation already won the race; that action may start or continue
     * after this method returns. Closure does not itself invoke the action, cancel the
     * timed operation, or reset {@link #isExpired()} after expiration.
     */
    @Override
    public void close() {
        pending.set(false);
        scheduled.cancel(false);
        scheduler.shutdown();
    }
}
