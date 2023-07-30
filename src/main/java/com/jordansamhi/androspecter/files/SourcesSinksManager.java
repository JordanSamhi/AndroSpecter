package com.jordansamhi.androspecter.files;

import com.jordansamhi.androspecter.MethodSignatureConverter;
import com.jordansamhi.androspecter.utils.Constants;
import soot.jimple.infoflow.android.data.AndroidMethod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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
 * A singleton manager class for managing source and sink methods in an Android application.
 * <p>
 * This class extends the FileLoader class and is responsible for loading, storing, and providing access to
 * source and sink methods. The sources and sinks are represented as sets of AndroidMethod objects.
 * <p>
 * The sources and sinks are loaded from a file specified by the abstract getFile() method, which must be
 * implemented by any concrete subclass. The file is assumed to contain a collection of strings, each representing
 * a method and its type (source or sink). The method and type are separated by a "|". The type is expected to be
 * either "source" or "sink", and the method is expected to be a Jimple signature of the method.
 * <p>
 * This class is a singleton, meaning there can only be one instance of it during the execution of the application.
 * The instance can be accessed by calling the v() method.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class SourcesSinksManager extends FileLoader {

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


    public static SourcesSinksManager v() {
        if (instance == null) {
            instance = new SourcesSinksManager();
        }
        return instance;
    }

    /**
     * Processes a string representing a method signature and adds it to the appropriate set.
     *
     * @param methodString The string to process. Should be in the format "type|signature".
     */
    private void processMethodString(String methodString) {
        String[] split = methodString.split("\\|");
        if (split.length == 2) {
            String type = split[0];
            String signature = split[1];
            MethodSignatureConverter msc = MethodSignatureConverter.v();
            AndroidMethod am = new AndroidMethod(msc.getMethodNameFromJimpleSignature(signature),
                    msc.getParametersNamesFromJimpleSignature(signature),
                    msc.getReturnNameFromJimpleSignature(signature),
                    msc.getClassNameFromJimpleSignature(signature));
            if (type.equals(Constants.SOURCE)) {
                this.sources.add(am);
            } else if (type.equals(Constants.SINK)) {
                this.sinks.add(am);
            }
        }
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
            processMethodString(method);
        }
    }

    /**
     * Loads source and sink methods from a given file.
     * <p>
     * This method parses lines from the file, where each line is assumed to be a string in the format "type|signature".
     * The "type" is expected to be either "source" or "sink", and "signature" is the Jimple signature of the method.
     * An AndroidMethod object is created from each valid signature and is added to either the sources or sinks collection,
     * depending on the type.
     *
     * @param file The file containing the source and sink methods' signatures.
     */
    public void loadSourcesSinksFromFile(File file) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String method;
            while ((method = reader.readLine()) != null) {
                processMethodString(method);
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
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