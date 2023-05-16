package com.jordansamhi.utils.files;

import com.jordansamhi.utils.utils.Constants;
import soot.SootClass;

/*-
 * #%L
 * Utils
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
 * Manages system classes by checking for system class membership of given Soot classes.
 * Loads the list of system classes from a configuration file at startup.
 */
public class SystemManager extends FileLoader {

    private static SystemManager instance;

    private SystemManager() {
        super();
    }

    public static SystemManager v() {
        if (instance == null) {
            instance = new SystemManager();
        }
        return instance;
    }

    @Override
    protected String getFile() {
        return Constants.SYSTEMS_FILE;
    }


    /**
     * Checks whether the given SootClass is a system class.
     *
     * @param sc the SootClass to check for system class membership
     * @return true if the SootClass is a system class, false otherwise
     */
    public boolean isSystemClass(SootClass sc) {
        for (String s : this.items) {
            if (sc.getName().startsWith(String.format("%s.", s))) {
                return true;
            }
        }
        return false;
    }
}
