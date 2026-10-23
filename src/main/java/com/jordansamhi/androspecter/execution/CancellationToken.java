package com.jordansamhi.androspecter.execution;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Read-only cooperative cancellation state supplied by the caller. */
public interface CancellationToken {
    boolean isCancellationRequested();

    /**
     * Registers an action invoked once when cancellation is requested. Registration after
     * cancellation invokes the action immediately. Closing the registration prevents a pending
     * invocation; it does not interrupt an action that is already running.
     *
     * @param action callback, which should return promptly
     * @return a registration the caller can close when the action is no longer needed
     */
    Registration onCancellation(Runnable action);

    /** Throws on requested cancellation or thread interruption, preserving the interrupt flag. */
    default void throwIfCancellationRequested() {
        if (isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operation cancelled");
        }
    }

    /** Returns a token that is never explicitly cancelled. */
    static CancellationToken none() {
        return NeverCancelled.INSTANCE;
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    final class NeverCancelled {
        private static final CancellationToken INSTANCE = new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

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
