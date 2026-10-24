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

/**
 * This class provides a singleton instance of a temporary folder utility class
 * that allows to get the path to the default temporary directory for the current system.
 *
 * @author Jordan Samhi
 */
public class TmpFolder {

    private static TmpFolder instance;

    private TmpFolder() {}

    /**
     * Returns the shared accessor for the JVM's temporary directory property.
     * This does not create or reserve a directory for an analysis.
     *
     * @return the legacy singleton utility
     */
    public static TmpFolder v() {
        if (instance == null) {
            instance = new TmpFolder();
        }
        return instance;
    }

    /**
     * Reads the current {@code java.io.tmpdir} system property. The result is not
     * cached, and the directory is neither created nor checked for existence or writability.
     *
     * @return configured temporary-directory path, or null if the property was removed
     * @throws SecurityException if a security manager denies access to the property
     */
    public String get() {
        return System.getProperty("java.io.tmpdir");
    }
}
