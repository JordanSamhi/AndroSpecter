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
     * @deprecated Use {@link Deadline#after(Duration, Runnable)} or an explicit callback.
     */
    @Deprecated
    public TimeOut(int n) {
        this(n, () -> Writer.v().pwarning("Timeout reached; cancellation requested"));
    }

    /**
     * Creates an unlaunched timeout with a caller-defined callback. Expiration first signals
     * {@link #cancellationToken()}, then invokes the callback on a daemon thread.
     *
     * @param n duration in seconds; zero retains the legacy default of 60 seconds
     * @param action callback invoked once on expiration
     */
    public TimeOut(int n, Runnable action) {
        if (n < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        this.action = Objects.requireNonNull(action, "action");
        this.timeout = n != 0 ? n : 60;
    }

    /**
     * Launches the timer with the specified timeout duration.
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
     * Cancels the currently running timer, if any.
     */
    public synchronized void cancel() {
        this.cancelled = true;
        if (this.timer != null) {
            this.timer.close();
        }
    }

    /** Read-only token signalled by expiration; cancelling the timer does not signal it. */
    public CancellationToken cancellationToken() {
        return cancellation.token();
    }

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
