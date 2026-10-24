package com.jordansamhi.androspecter.files;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

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
 * Base class for loading UTF-8 classpath resources into a mutable set of trimmed lines.
 * This does not read arbitrary filesystem paths. Subclasses supply a resource name
 * through {@link #getFile()}. Both that method and {@link #loadFile(String)} are invoked
 * during superclass construction, before subclass fields are initialized. Instances
 * and their entry sets are not thread-safe.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public abstract class FileLoader {
    /** Loaded, deduplicated entries; mutable and unordered, without blank or whole-line comment entries. */
    protected Set<String> items;

    /**
     * Creates the entry set and loads {@link #getFile()} immediately. Overrides of
     * {@code getFile} and {@link #loadFile(String)} must be usable before the subclass's
     * constructor or field initializers run.
     *
     * @throws NullPointerException if the subclass returns a null resource name
     * @throws IllegalStateException if the resource is missing
     * @throws UncheckedIOException if the resource cannot be read
     */
    protected FileLoader() {
        this.items = new HashSet<>();
        this.loadFile(this.getFile());
    }

    /**
     * Supplies the resource loaded during construction. A leading slash resolves from
     * the classpath root; otherwise the name is relative to the concrete subclass's package.
     *
     * @return non-null classpath resource name, for example {@code /libraries.txt}
     */
    protected abstract String getFile();

    /**
     * Adds UTF-8 classpath resource entries to {@link #items}, retaining existing entries.
     * Lines are trimmed; blank lines and lines beginning with {@code #} after trimming
     * are skipped. Duplicate lines are collapsed. Inline comments are not removed.
     * A read failure may leave entries from earlier lines in the set.
     *
     * @param file non-null resource name resolved as described by {@link #getFile()}
     * @throws NullPointerException if {@code file} is null
     * @throws IllegalStateException if the resource is missing
     * @throws UncheckedIOException if the resource cannot be read
     */
    protected void loadFile(String file) {
        InputStream stream = this.getClass().getResourceAsStream(file);
        if (stream == null) {
            throw new IllegalStateException("Classpath resource not found: " + file);
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("#") && !line.isEmpty()) {
                    this.items.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource: " + file, e);
        }
    }
}
