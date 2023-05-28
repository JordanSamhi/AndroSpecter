package com.jordansamhi.androspecter.files;

import com.jordansamhi.androspecter.utils.Constants;
import soot.SootClass;

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
 * The LibrariesManager class is responsible for loading and managing the list of known library packages.
 * It extends the {@link FileLoader} abstract class to load the list of library packages from a file at startup.
 * The singleton pattern is used to ensure only one instance of this class is created.
 */
public class LibrariesManager extends FileLoader {

    private static LibrariesManager instance;

    private LibrariesManager() {
        super();
    }

    public static LibrariesManager v() {
        if (instance == null) {
            instance = new LibrariesManager();
        }
        return instance;
    }

    @Override
    protected String getFile() {
        return Constants.LIBRARIES_FILE;
    }

    /**
     * Checks whether the given SootClass belongs to a library.
     *
     * @param sc the SootClass to check for library membership
     * @return true if the SootClass belongs to a library, false otherwise
     */
    public boolean isLibrary(SootClass sc) {
        for (String s : this.items) {
            if (sc.getName().startsWith(String.format("%s.", s))) {
                return true;
            }
        }
        return false;
    }
}
