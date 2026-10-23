package com.jordansamhi.androspecter.execution;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Read-only cooperative cancellation state supplied by the caller. Tokens from
 * {@link CancellationSource} are safe to read and register callbacks on across threads.
 * Observing a request does not forcibly stop the operation or terminate the JVM.
 */
public interface CancellationToken {
    /**
     * Tests whether this token has received an explicit cancellation request. This query
     * alone does not check the calling thread's interrupt status.
     *
     * @return true once cancellation has been requested for this token
     */
    boolean isCancellationRequested();

    /**
     * Registers an action invoked once when cancellation is requested. Registration after
     * cancellation invokes the action immediately. Closing the registration prevents a pending
     * invocation; it does not interrupt an action that is already running.
     * Tokens from CancellationSource run callbacks synchronously on the cancelling thread,
     * or on the registering thread when already cancelled. Runtime exceptions from those
     * callbacks are logged, allowing remaining callbacks to proceed.
     *
     * @param action callback, which should return promptly
     * @return a registration the caller can close when the action is no longer needed
     * @throws NullPointerException if {@code action} is null
     */
    Registration onCancellation(Runnable action);

    /**
     * Checks explicit cancellation and the calling thread's interrupt status. Use this at
     * cooperative checkpoints; it does not wait for cancellation or clear the interrupt flag.
     *
     * @throws CancellationException if this token is cancelled or this thread is interrupted
     */
    default void throwIfCancellationRequested() {
        if (isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operation cancelled");
        }
    }

    /**
     * Returns a shared token that is never explicitly cancelled. Registering a callback
     * validates it but never runs it. The default {@link #throwIfCancellationRequested()}
     * method still detects thread interruption.
     *
     * @return the immutable token without an associated cancellation source
     */
    static CancellationToken none() {
        return NeverCancelled.INSTANCE;
    }

    /**
     * Handle for removing a cancellation callback when its operation scope ends. Closing
     * the handle does not cancel the underlying operation or clear an existing request.
     */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        /**
         * Prevents callback invocation if it has not started. This is idempotent and does
         * not interrupt or wait for a callback already running. A racing callback may
         * therefore still be executing when this method returns.
         */
        @Override
        void close();
    }

    /** Storage for the shared token returned by {@link CancellationToken#none()}. */
    final class NeverCancelled {
        private static final CancellationToken INSTANCE = new CancellationToken() {
            /** {@inheritDoc} */
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            /** {@inheritDoc} */
            @Override
            public Registration onCancellation(Runnable action) {
                Objects.requireNonNull(action, "action");
                return () -> { };
            }
        };

        private NeverCancelled() {
        }
    }
}
