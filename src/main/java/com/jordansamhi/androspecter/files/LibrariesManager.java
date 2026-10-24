package com.jordansamhi.androspecter.files;

import com.jordansamhi.androspecter.utils.Constants;
import soot.SootClass;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * @author Jordan Samhi
 */
public class LibrariesManager extends FileLoader {

    private static LibrariesManager instance;
    private final Set<String> libraries = ConcurrentHashMap.newKeySet();

    private LibrariesManager() {
        super();
    }

    /**
     * Returns the shared classifier, loading bundled package prefixes on first use.
     * This classification is independent of Soot's application-class flag.
     *
     * @return the singleton library classifier
     * @throws IllegalStateException if the bundled prefix resource is missing
     * @throws java.io.UncheckedIOException if the resource cannot be read
     */
    public static synchronized LibrariesManager v() {
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
     * Classifies a borrowed Soot class using bundled package prefixes. Matching is
     * case-sensitive and requires a dot after the prefix, so {@code com.example}
     * matches {@code com.example.Widget} but not {@code com.examples.Widget}.
     * This is a name-based heuristic, independent of Soot's application/library flag;
     * it does not establish the origin of a class. Only class names are cached.
     *
     * @param sc non-null class from the caller's current Soot scene; not retained
     * @return true if a bundled library-package prefix matches its name
     * @throws NullPointerException if {@code sc} is null
     */
    public boolean isLibrary(SootClass sc) {
        String className = sc.getName();
        if (libraries.contains(className)) {
            return true;
        }

        boolean isLibrary = items.parallelStream()
                .anyMatch(item -> className.startsWith(item + "."));

        if (isLibrary) {
            libraries.add(className);
        }

        return isLibrary;
    }
}
