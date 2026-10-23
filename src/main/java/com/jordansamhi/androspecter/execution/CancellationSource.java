package com.jordansamhi.androspecter.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Caller-owned, thread-safe cancellation source. Cancellation never exits the JVM. */
public final class CancellationSource implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CancellationSource.class);
    private final Object lock = new Object();
    private final Set<Callback> callbacks = new LinkedHashSet<>();
    private volatile boolean cancelled;
    private final CancellationToken token = new CancellationToken() {
        @Override
        public boolean isCancellationRequested() {
            return cancelled;
        }

        @Override
        public Registration onCancellation(Runnable action) {
            return register(action);
        }
    };

    public CancellationToken token() {
        return token;
    }

    /**
     * Requests cancellation and runs registered actions synchronously on the calling thread.
     * Runtime exceptions from an action are logged and do not prevent the remaining actions.
     * Cancellation is cooperative and does not forcibly stop any thread.
     *
     * @return true only for the first cancellation request
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

    /** Requests cancellation when the caller's operation scope closes. */
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

        @Override
        public void close() {
            pending.set(false);
            synchronized (lock) {
                callbacks.remove(this);
            }
        }
    }
}
