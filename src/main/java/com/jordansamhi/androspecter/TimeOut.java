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
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/**
 * The TimeOut class provides a simple mechanism for setting a timeout duration,
 * launching a timer, and triggering a task when the timeout expires.
 *
 * @author Jordan Samhi
 */
public class TimeOut {

    private Timer timer;
    private final Runnable action;
    private final int timeout;
    private boolean cancelled;

    /**
     * Constructs a new TimeOut object with a specified timeout duration.
     *
     * @param n the timeout duration in seconds; if zero, the timeout duration defaults to 60 seconds
     */
    public TimeOut(int n) {
        this(n, () -> {
            Writer.v().pwarning("Timeout reached!");
            Writer.v().pwarning("Ending program...");
            System.exit(1);
        });
    }

    TimeOut(int n, Runnable action) {
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
        this.timer = new Timer("androspecter-timeout", true);
        this.timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    action.run();
                } finally {
                    cancelTimer();
                }
            }
        }, TimeUnit.SECONDS.toMillis(this.timeout));
    }

    /**
     * Cancels the currently running timer, if any.
     */
    public synchronized void cancel() {
        cancelTimer();
    }

    private synchronized void cancelTimer() {
        this.cancelled = true;
        if (this.timer != null) {
            this.timer.cancel();
        }
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
