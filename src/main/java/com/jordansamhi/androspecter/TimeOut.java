package com.jordansamhi.androspecter;

/*-
 * #%L
 * AndroSpecter
 *
 * %%
 * Copyright (C) 2023 Jordan Samhi
 * All rights reserved
 *
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

import com.jordansamhi.androspecter.printers.Writer;
import com.jordansamhi.androspecter.execution.CancellationSource;
import com.jordansamhi.androspecter.execution.CancellationToken;
import com.jordansamhi.androspecter.execution.Deadline;
import java.time.Duration;
import java.util.Objects;

/**
 * The TimeOut class provides a simple mechanism for setting a timeout duration,
 * launching a timer, and notifying the caller when the timeout expires.
 * This class never terminates the JVM. Prefer {@link Deadline} for a Duration-based API.
 *
 * @author Jordan Samhi
 */
public class TimeOut implements AutoCloseable {

    private Deadline timer;
    private final CancellationSource cancellation = new CancellationSource();
    private final Runnable action;
    private final int timeout;
    private boolean cancelled;

    /**
     * Constructs a timeout that signals {@link #cancellationToken()} and logs expiration.
     * Unlike older releases, this constructor never exits the JVM. Callers must observe the
     * token or use {@link #TimeOut(int, Runnable)} to choose what expiration should do.
     *
     * @param n the timeout duration in seconds; if zero, the timeout duration defaults to 60 seconds
     * @throws IllegalArgumentException if {@code n} is negative
     * @deprecated Use {@link Deadline#after(Duration, Runnable)} or an explicit callback.
     */
    @Deprecated
    public TimeOut(int n) {
        this(n, () -> Writer.v().pwarning("Timeout reached; cancellation requested"));
    }

    /**
     * Creates an unlaunched timeout with a caller-defined callback. Expiration first signals
     * {@link #cancellationToken()}, then invokes the callback on a daemon thread.
     * No timer thread is created until {@link #launch()}. The callback should return promptly;
     * runtime exceptions are logged by the underlying Deadline.
     *
     * @param n duration in seconds; zero retains the legacy default of 60 seconds
     * @param action callback invoked once on expiration
     * @throws IllegalArgumentException if {@code n} is negative
     * @throws NullPointerException if {@code action} is null
     */
    public TimeOut(int n, Runnable action) {
        if (n < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        this.action = Objects.requireNonNull(action, "action");
        this.timeout = n != 0 ? n : 60;
    }

    /**
     * Starts this one-shot timer, counting the configured seconds from this call. A timer
     * cannot be relaunched, including after expiration or cancellation. Expiration requests
     * cooperative cancellation; it does not stop a worker or terminate the JVM.
     *
     * @throws IllegalStateException if this timer has already been launched or cancelled
     */
    public synchronized void launch() {
        if (cancelled || timer != null) {
            throw new IllegalStateException("Timer has already been launched or cancelled");
        }
        this.timer = Deadline.after(Duration.ofSeconds(this.timeout), () -> {
            cancellation.cancel();
            action.run();
        });
    }

    /**
     * Disarms this timer, including before launch, without signalling its cancellation token.
     * This method is idempotent and prevents later launch. If expiration has already begun,
     * it neither waits for nor interrupts the callback, and cannot undo cancellation.
     */
    public synchronized void cancel() {
        this.cancelled = true;
        if (this.timer != null) {
            this.timer.close();
        }
    }

    /**
     * Returns the read-only token signalled on expiration. Calling {@link #cancel()} or
     * {@link #close()} only disarms the timer; neither explicitly cancels this token.
     *
     * @return the token callers may observe or pass to a cooperative operation
     */
    public CancellationToken cancellationToken() {
        return cancellation.token();
    }

    /**
     * Disarms the timer by calling {@link #cancel()}. This does not request operation
     * cancellation or wait for a callback that has already started.
     */
    @Override
    public void close() {
        cancel();
    }

    /**
     * Gets the timeout duration of this TimeOut object.
     *
     * @return the timeout duration in seconds
     */
    public int getTimeout() {
        return this.timeout;
    }

}
