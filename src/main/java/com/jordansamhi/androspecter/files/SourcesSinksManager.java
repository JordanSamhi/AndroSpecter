package com.jordansamhi.androspecter.files;

import com.jordansamhi.androspecter.utils.Constants;
import soot.jimple.infoflow.android.data.AndroidMethod;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Mutable source/sink definitions initialized from the library's bundled classpath resource.
 * Prefer a new instance per analysis or {@link SourceSinkConfiguration} for an immutable
 * configuration. Records use {@code TYPE|signature}, where the case-sensitive type is
 * {@code SOURCE} or {@code SINK} and the signature uses full Jimple method syntax.
 * <p>
 * Getters expose live mutable sets of mutable {@link AndroidMethod} descriptors, not Soot
 * methods resolved in a scene. Instances are not thread-safe. The deprecated {@link #v()}
 * accessor retains shared mutable state for compatibility; synchronized singleton creation
 * does not make operations on its contents thread-safe.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class SourcesSinksManager extends FileLoader {

    private static final Pattern SIGNATURE = Pattern.compile(
            "^<([^\\s:<>]+):\\s+([^\\s<>(),|]+)\\s+([^\\s()|]+)\\(([^()]*)\\)>$");

    /**
     * Singleton instance of the SourcesSinksManager.
     */
    private static SourcesSinksManager instance;

    /**
     * Set of source methods represented as AndroidMethod objects.
     */
    private final Set<AndroidMethod> sources;

    /**
     * Set of sink methods represented as AndroidMethod objects.
     */
    private final Set<AndroidMethod> sinks;


    /**
     * Creates independent, mutable source/sink sets initialized from the bundled UTF-8 definitions.
     * This does not read or reuse the legacy singleton's mutable sets.
     *
     * @throws IllegalStateException if the bundled classpath resource is missing
     * @throws UncheckedIOException if the bundled resource cannot be read
     * @throws IllegalArgumentException if a bundled record is malformed
     */
    public SourcesSinksManager() {
        this(true);
    }

    SourcesSinksManager(boolean includeBundled) {
        super();
        this.sources = new HashSet<>();
        this.sinks = new HashSet<>();
        if (includeBundled) {
            this.loadSourcesSinks();
        }
    }


    /**
     * Returns the process-wide legacy manager, creating it from bundled definitions on first use.
     * Additions, removals, and resets affect all callers sharing this instance.
     *
     * @return non-null shared mutable manager
     * @throws IllegalStateException if initial loading cannot find the bundled resource
     * @throws UncheckedIOException if initial loading cannot read the bundled resource
     * @throws IllegalArgumentException if an initially loaded bundled record is malformed
     * @deprecated use {@link #SourcesSinksManager()} for independent mutable state or
     *             {@link SourceSinkConfiguration} for immutable definitions
     */
    @Deprecated
    public static synchronized SourcesSinksManager v() {
        if (instance == null) {
            instance = new SourcesSinksManager();
        }
        return instance;
    }

    /**
     * Processes a string representing a method signature and adds it to the appropriate set.
     *
     * @param methodString The string to process. Should be in the format "TYPE|signature".
     * @param location The resource name or file and line number for error reporting.
     * @param parsedSources The destination for valid sources.
     * @param parsedSinks The destination for valid sinks.
     */
    private void processMethodString(String methodString, String location,
                                     Set<AndroidMethod> parsedSources, Set<AndroidMethod> parsedSinks) {
        String record = methodString.trim();
        if (record.isEmpty() || record.startsWith("#")) {
            return;
        }
        String[] split = record.split("\\|", -1);
        if (split.length != 2) {
            throw invalidRecord(location, record, "expected TYPE|<class: returnType method(parameters)>");
        }
        String type = split[0].trim();
        if (!Constants.SOURCE.equals(type) && !Constants.SINK.equals(type)) {
            throw invalidRecord(location, record, "type must be SOURCE or SINK");
        }
        AndroidMethod method;
        try {
            method = parseSignature(split[1].trim(), location);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(ex.getMessage() + "; input record: " + record, ex);
        }
        if (Constants.SOURCE.equals(type)) {
            parsedSources.add(method);
        } else {
            parsedSinks.add(method);
        }
    }

    static AndroidMethod parseSignature(String value, String location) {
        String record = java.util.Objects.requireNonNull(value, "signature").trim();
        Matcher signature = SIGNATURE.matcher(record);
        if (!signature.matches()) {
            throw invalidRecord(location, record, "expected a Jimple method signature <class: returnType method(parameters)>");
        }
        List<String> parameters = new ArrayList<>();
        String parameterList = signature.group(4).trim();
        if (!parameterList.isEmpty()) {
            for (String parameter : parameterList.split(",", -1)) {
                String parameterType = parameter.trim();
                if (!parameterType.matches("[^\\s<>(),|]+")) {
                    throw invalidRecord(location, record, "parameter types must be nonempty and comma-separated");
                }
                parameters.add(parameterType);
            }
        }
        return new AndroidMethod(signature.group(3), parameters, signature.group(2), signature.group(1));
    }

    private static IllegalArgumentException invalidRecord(String location, String record, String reason) {
        return new IllegalArgumentException("Invalid source/sink record at " + location + ": " + reason + ". Record: " + record);
    }

    /**
     * A private method that loads source and sink methods.
     * <p>
     * This method parses the items field, which is assumed to be a collection of strings. Each string is split into two parts separated by "|".
     * The first part is expected to indicate the type (source or sink) and the second part is the Jimple signature of the method.
     * The method then creates an AndroidMethod object from the signature, and depending on the type, adds it to either the sources or sinks collection.
     */
    private void loadSourcesSinks() {
        for (String method : this.items) {
            processMethodString(method, getFile(), sources, sinks);
        }
    }

    /**
     * Adds definitions from a UTF-8 file while retaining existing source/sink entries.
     * Records use {@code SOURCE|<example.Input: java.lang.String read()>} or the corresponding
     * {@code SINK|...} form. Surrounding whitespace is ignored; blank lines and lines whose
     * first nonwhitespace character is {@code #} are skipped. Constructors and array types
     * are accepted in method signatures. Methods are described without loading their classes.
     * <p>
     * Definitions are merged only after the entire file has been read and validated. A read
     * or parse failure leaves the existing sets unchanged; duplicate entries are deduplicated.
     *
     * @param file non-null file containing source/sink records
     * @throws NullPointerException if {@code file} is null
     * @throws IllegalArgumentException if a record is malformed, including its file and line number
     * @throws UncheckedIOException if the file cannot be read
     */
    public void loadSourcesSinksFromFile(File file) {
        Set<AndroidMethod> parsedSources = new HashSet<>();
        Set<AndroidMethod> parsedSinks = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String method;
            int lineNumber = 0;
            while ((method = reader.readLine()) != null) {
                lineNumber++;
                processMethodString(method, file + ":" + lineNumber, parsedSources, parsedSinks);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read source/sink file: " + file, e);
        }
        // Preserve additive loading, but do not partially apply malformed files.
        sources.addAll(parsedSources);
        sinks.addAll(parsedSinks);
    }

    /**
     * Clears both live sets and restores the bundled records read when this manager was created.
     * This does not reread the classpath resource or previously loaded custom files. Existing
     * references returned by the getters observe the reset because the sets are reused.
     *
     * @throws IllegalArgumentException if a retained bundled record is malformed
     */
    public void reset() {
        sources.clear();
        sinks.clear();
        loadSourcesSinks();
    }


    /**
     * Returns the live mutable source set. Adding or removing descriptors changes this manager;
     * neither the set nor its mutable descriptors are defensively copied. The descriptors do
     * not refer to live Soot methods. Avoid mutating a descriptor in a way that changes its
     * equality/hash value while it is stored in the set.
     *
     * @return non-null live source descriptors, possibly empty, with no iteration-order guarantee
     */
    public Set<AndroidMethod> getSources() {
        return this.sources;
    }

    /**
     * Returns the live mutable sink set. Adding or removing descriptors changes this manager;
     * neither the set nor its mutable descriptors are defensively copied. The descriptors do
     * not refer to live Soot methods. Avoid mutating a descriptor in a way that changes its
     * equality/hash value while it is stored in the set.
     *
     * @return non-null live sink descriptors, possibly empty, with no iteration-order guarantee
     */
    public Set<AndroidMethod> getSinks() {
        return this.sinks;
    }


    @Override
    protected String getFile() {
        return Constants.SOURCES_SINKS_FILE;
    }
}
