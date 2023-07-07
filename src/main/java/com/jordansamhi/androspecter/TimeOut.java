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
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The TimeOut class provides a simple mechanism for setting a timeout duration,
 * launching a timer, and triggering a task when the timeout expires.
 *
 * @author Jordan Samhi
 */
public class TimeOut {

    private final Timer timer;
    private final TimerTask exitTask;
    private final int timeout;

    /**
     * Constructs a new TimeOut object with a specified timeout duration.
     *
     * @param n the timeout duration in seconds; if zero, the timeout duration defaults to 60 seconds
     */
    public TimeOut(int n) {
        this.timer = new Timer();
        this.exitTask = new TimerTask() {
            @Override
            public void run() {
                Writer.v().pwarning("Timeout reached!");
                Writer.v().pwarning("Ending program...");
                System.exit(1);
            }
        };
        this.timeout = n != 0 ? n : 60;
    }

    /**
     * Launches the timer with the specified timeout duration.
     */
    public void launch() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, this.timeout);
        this.timer.schedule(this.exitTask, c.getTime());
    }

    /**
     * Cancels the currently running timer, if any.
     */
    public void cancel() {
        this.timer.cancel();
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