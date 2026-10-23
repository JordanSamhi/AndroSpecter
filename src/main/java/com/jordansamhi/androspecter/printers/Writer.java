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

    /**
     * Creates a facade using the SLF4J logger named after Writer. The consuming application
     * supplies the logging provider, destinations, and level configuration.
     */
    public Writer() {
        this(LoggerFactory.getLogger(Writer.class));
    }

    /**
     * Creates a facade backed by a caller-supplied logger without changing its configuration.
     *
     * @param logger destination for all messages from this facade
     * @throws NullPointerException if {@code logger} is null
     */
    public Writer(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Returns the shared facade using the Writer-class logger. Obtain a dedicated instance
     * with {@link #Writer(Logger)} when messages should use a different logger.
     *
     * @return the shared immutable logging facade
     */
    public static Writer v() {
        return INSTANCE;
    }

    /**
     * Sends a literal message to the logger at ERROR level, subject to provider configuration.
     *
     * @param s message passed unchanged to the logger
     */
    public void perror(String s) {
        logger.error(s);
    }


    /**
     * Sends a success message at INFO level; no additional decoration is applied.
     *
     * @param s message passed unchanged to the logger
     */
    public void psuccess(String s) {
        logger.info(s);
    }

    /**
     * Sends a literal message to the logger at WARN level, subject to provider configuration.
     *
     * @param s message passed unchanged to the logger
     */
    public void pwarning(String s) {
        logger.warn(s);
    }

    /**
     * Sends a literal message to the logger at INFO level, subject to provider configuration.
     *
     * @param s message passed unchanged to the logger
     */
    public void pinfo(String s) {
        logger.info(s);
    }
}
