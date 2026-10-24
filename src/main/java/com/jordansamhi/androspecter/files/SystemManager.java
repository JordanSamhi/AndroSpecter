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
 * Legacy classifier based on system-package prefixes from the bundled classpath resource.
 * This is a name-based heuristic, independent of Soot's application/library flag.
 * Singleton initialization is not synchronized; use it on the thread owning the Soot scene.
 *
 * @author Jordan Samhi
 */
public class SystemManager extends FileLoader {

    private static SystemManager instance;

    private SystemManager() {
        super();
    }

    /**
     * Returns the shared classifier, loading bundled system-package prefixes on first use.
     *
     * @return the legacy singleton system-class classifier
     * @throws IllegalStateException if the bundled prefix resource is missing
     * @throws java.io.UncheckedIOException if the resource cannot be read
     */
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
     * Checks a class name against the bundled prefixes, requiring a dot after each prefix.
     * Matching is case-sensitive; no superclass traversal or origin verification is performed.
     * The class is borrowed only for this call and is not cached.
     *
     * @param sc non-null class from the caller's current Soot scene
     * @return true if a bundled system-package prefix matches its name
     * @throws NullPointerException if {@code sc} is null
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
