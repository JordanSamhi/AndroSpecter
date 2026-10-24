package com.jordansamhi.androspecter.execution;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Read-only cooperative cancellation state supplied by the caller. Tokens from
 * {@link CancellationSource} are safe to read and register callbacks on across threads.
 * Observing a request does not forcibly stop the operation or terminate the JVM.
 * Custom implementations should preserve a cancellation request once made and return a
 * non-null registration from {@link #onCancellation(Runnable)}. They must support concurrent
 * polling/registration when the application requests cancellation from another thread.
 * These objects are independent of Soot and remain usable after an analysis closes or resets.
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
     * Registers an action invoked at most once when cancellation is requested, unless its
     * registration is closed before execution is claimed. Registration after cancellation
     * invokes the action before returning. The token returned by {@link #none()} never invokes
     * callbacks. Closing a registration cannot stop an invocation that already won the race,
     * even if the action has not yet entered its body.
     * Tokens from CancellationSource run callbacks synchronously on the cancelling thread,
     * or on the registering thread when already cancelled. Runtime exceptions from those
     * callbacks are logged, allowing remaining callbacks to proceed. An Error from a source
     * callback is not caught and can prevent later callbacks from running. Keep callback work
     * short and avoid waiting for the operation whose cancellation it signals.
     *
     * @param action non-null callback, which should return promptly
     * @return a non-null registration the caller owns and can close when the action is no longer needed
     * @throws NullPointerException if {@code action} is null
     * @throws Error if an immediately invoked callback throws an Error
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
     * Registrations returned by {@link CancellationSource} are safe to close from any thread.
     */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        /**
         * Disarms callback execution if cancellation has not already claimed that invocation.
         * This is idempotent and does not interrupt or wait for a claimed callback. A callback
         * that wins the race may start or continue after this method returns.
         */
        @Override
        void close();
    }

    /**
     * Implementation holder for the shared token. Obtain that token through
     * {@link CancellationToken#none()}; this type has no caller-constructible instances.
     */
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
