package com.jordansamhi.androspecter.printers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

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

/**
 * Compatibility logging facade. Messages use SLF4J; the consuming application chooses
 * its logging provider and configuration. This class does not write directly to the console.
 *
 * @author Jordan Samhi
 */
public class Writer {

    private static final Writer INSTANCE = new Writer();
    private final Logger logger;

    public Writer() {
        this(LoggerFactory.getLogger(Writer.class));
    }

    /** Uses the supplied logger without changing application logging configuration. */
    public Writer(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public static Writer v() {
        return INSTANCE;
    }

    /**
     * Logs an error message.
     *
     * @param s the error message to print
     */
    public void perror(String s) {
        logger.error(s);
    }


    /**
     * Logs a success message at INFO level.
     *
     * @param s the success message to print
     */
    public void psuccess(String s) {
        logger.info(s);
    }

    /**
     * Logs a warning message.
     *
     * @param s the warning message to print
     */
    public void pwarning(String s) {
        logger.warn(s);
    }

    /**
     * Logs an informational message.
     *
     * @param s the informational message to print
     */
    public void pinfo(String s) {
        logger.info(s);
    }
}
