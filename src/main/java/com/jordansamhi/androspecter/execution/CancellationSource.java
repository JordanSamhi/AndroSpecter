package com.jordansamhi.androspecter.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caller-owned, thread-safe cancellation source, initially not cancelled. Create a source
 * for each operation, give its {@link #token()} to the operation, and call {@link #cancel()}
 * from the controlling thread when needed. Closing the source also requests cancellation.
 * Its public no-argument constructor creates an independent source. Cancellation is
 * permanent, cooperative, and never interrupts a thread or exits the JVM by itself.
 * Keep the source open until the operation finishes; closing it requests cancellation,
 * while closing a registration only removes that callback.
 * <pre>{@code
 * try (CancellationSource source = new CancellationSource();
 *      Deadline deadline = Deadline.after(java.time.Duration.ofMinutes(2), source::cancel)) {
 *     // Pass source.token() to the operation before starting it.
 * }
 * }</pre>
 */
public final class CancellationSource implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CancellationSource.class);
    private final Object lock = new Object();
    private final Set<Callback> callbacks = new LinkedHashSet<>();
    private volatile boolean cancelled;
    private final CancellationToken token = new CancellationToken() {
        /** {@inheritDoc} */
        @Override
        public boolean isCancellationRequested() {
            return cancelled;
        }

        /** {@inheritDoc} */
        @Override
        public Registration onCancellation(Runnable action) {
            return register(action);
        }
    };

    /**
     * Returns the read-only view shared by all operations observing this source. Each call
     * returns the same token; closing the source permanently leaves that token cancelled.
     * The token remains usable after source closure, and later registrations are invoked
     * synchronously on their registering thread because cancellation is already requested.
     *
     * @return this source's thread-safe cancellation token
     */
    public CancellationToken token() {
        return token;
    }

    /**
     * Requests cancellation and runs registered actions synchronously on the calling thread.
     * Actions still registered when cancellation is requested are visited in registration
     * order; a concurrent registration closure can disarm an action not yet claimed for execution.
     * Runtime exceptions from an action are logged and do not prevent the remaining actions.
     * Cancellation is cooperative and does not forcibly stop any thread.
     * The request is visible before callbacks run. Callbacks should return promptly because
     * this method does not return until the callbacks it invokes finish; a concurrent repeated
     * request can return false while those callbacks are still running. Callback Errors propagate
     * and can prevent remaining callbacks from running; the source remains cancelled.
     *
     * @return true only for the first cancellation request
     * @throws Error if a callback throws an Error
     */
    public boolean cancel() {
        List<Callback> pending;
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            pending = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        pending.forEach(Callback::fire);
        return true;
    }

    private CancellationToken.Registration register(Runnable action) {
        Callback callback = new Callback(Objects.requireNonNull(action, "action"));
        synchronized (lock) {
            if (!cancelled) {
                callbacks.add(callback);
                return callback;
            }
        }
        callback.fire();
        return callback;
    }

    /**
     * Requests cancellation, with the same synchronous callback behavior as {@link #cancel()}.
     * This is safe to repeat and does not wait for an operation to finish or interrupt its
     * thread. It signals cancellation rather than merely disposing of this source; whether
     * the operation stops depends on its cancellation checkpoints and registered callbacks.
     *
     * @throws Error if a callback throws an Error
     */
    @Override
    public void close() {
        cancel();
    }

    private final class Callback implements CancellationToken.Registration {
        private final AtomicBoolean pending = new AtomicBoolean(true);
        private final Runnable action;

        private Callback(Runnable action) {
            this.action = action;
        }

        private void fire() {
            if (pending.compareAndSet(true, false)) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    LOG.warn("Cancellation callback failed", e);
                }
            }
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            pending.set(false);
            synchronized (lock) {
                callbacks.remove(this);
            }
        }
    }
}
