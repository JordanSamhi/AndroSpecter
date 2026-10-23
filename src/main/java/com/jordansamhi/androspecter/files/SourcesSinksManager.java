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
 * A singleton manager class for managing source and sink methods in an Android application.
 * <p>
 * This class extends the FileLoader class and is responsible for loading, storing, and providing access to
 * source and sink methods. The sources and sinks are represented as sets of AndroidMethod objects.
 * <p>
 * The sources and sinks are loaded from a file specified by the abstract getFile() method, which must be
 * implemented by any concrete subclass. The file is assumed to contain a collection of strings, each representing
 * a method and its type (source or sink). The method and type are separated by a "|". The type is expected to be
 * either "SOURCE" or "SINK", and the method is expected to be a Jimple signature of the method.
 * <p>
 * This class is a singleton, meaning there can only be one instance of it during the execution of the application.
 * The instance can be accessed by calling the v() method.
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
     * A private constructor for the SourcesSinksManager class.
     * <p>
     * This constructor initializes the sources and sinks as new HashSets and
     * then loads sources and sinks by calling the loadSourcesSinks() method.
     */
    private SourcesSinksManager() {
        super();
        this.sources = new HashSet<>();
        this.sinks = new HashSet<>();
        this.loadSourcesSinks();
    }


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
        Matcher signature = SIGNATURE.matcher(split[1].trim());
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
        AndroidMethod method = new AndroidMethod(signature.group(3), parameters,
                signature.group(2), signature.group(1));
        if (Constants.SOURCE.equals(type)) {
            parsedSources.add(method);
        } else {
            parsedSinks.add(method);
        }
    }

    private IllegalArgumentException invalidRecord(String location, String record, String reason) {
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
     * Loads source and sink methods from a given file.
     * <p>
     * This method parses lines from the file, where each line is assumed to be a string in the format "type|signature".
     * The "type" is expected to be either "SOURCE" or "SINK", and "signature" is the Jimple signature of the method.
     * An AndroidMethod object is created from each valid signature and is added to either the sources or sinks collection,
     * depending on the type.
     *
     * Entries are added only after the complete UTF-8 file has been validated.
     * Blank lines and lines beginning with # are ignored.
     *
     * @param file The file containing the source and sink methods' signatures.
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
     * Removes custom entries and restores the bundled source/sink definitions.
     */
    public void reset() {
        sources.clear();
        sinks.clear();
        loadSourcesSinks();
    }


    /**
     * Returns the set of source methods.
     *
     * @return The set of AndroidMethod objects representing source methods.
     */
    public Set<AndroidMethod> getSources() {
        return this.sources;
    }

    /**
     * Returns the set of sink methods.
     *
     * @return The set of AndroidMethod objects representing sink methods.
     */
    public Set<AndroidMethod> getSinks() {
        return this.sinks;
    }


    @Override
    protected String getFile() {
        return Constants.SOURCES_SINKS_FILE;
    }
}