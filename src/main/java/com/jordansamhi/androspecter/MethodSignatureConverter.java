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

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy string helpers for canonical Jimple method signatures and JVM object-type descriptors.
 * Method helpers expect exactly the spacing in
 * {@code <example.Widget: void send(int,java.lang.String)>}: one space after the colon,
 * one after the return type, and no spaces in the parameter list. They neither resolve
 * Soot methods nor validate input syntax. Malformed input can produce incorrect text
 * or indexing exceptions. The converter has no analysis-specific state.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class MethodSignatureConverter {

    private static MethodSignatureConverter instance;

    /** Creates a stateless converter for Jimple method signatures. */
    public MethodSignatureConverter() {
    }

    /**
     * Returns the lazily created shared converter.
     *
     * @return the legacy shared instance; callers may also construct their own
     */
    public static MethodSignatureConverter v() {
        if (instance == null) {
            instance = new MethodSignatureConverter();
        }
        return instance;
    }

    /**
     * Extracts the declaring class name from a canonical full Jimple signature.
     * For {@code <example.Widget: void send(int)>}, returns {@code example.Widget}.
     *
     * @param sig non-null full signature with the canonical spacing described by this class
     * @return declaring class name without the opening angle bracket or colon
     * @throws NullPointerException if {@code sig} is null
     * @throws IndexOutOfBoundsException if the input lacks the expected token structure
     */
    public String getClassNameFromJimpleSignature(String sig) {
        String tmp = sig.split(" ")[0];
        return tmp.substring(1, tmp.length() - 1);
    }


    /**
     * Extracts the method name from a canonical full Jimple signature, retaining
     * special names such as {@code <init>} and {@code <clinit>}.
     *
     * @param sig non-null full signature with the canonical spacing described by this class
     * @return method name without return type or parameters
     * @throws NullPointerException if {@code sig} is null
     * @throws IndexOutOfBoundsException if the input lacks the expected token structure
     */
    public String getMethodNameFromJimpleSignature(String sig) {
        String tmp = sig.split(" ")[2];
        return tmp.substring(0, tmp.indexOf("("));
    }


    /**
     * Extracts the return-type text from a canonical full Jimple signature.
     *
     * @param sig non-null full signature with the canonical spacing described by this class
     * @return return type such as {@code void}, {@code int}, or {@code java.lang.String[]}
     * @throws NullPointerException if {@code sig} is null
     * @throws IndexOutOfBoundsException if the input lacks the expected token structure
     */
    public String getReturnNameFromJimpleSignature(String sig) {
        return sig.split(" ")[1];
    }


    /**
     * Extracts parameter types, not parameter variable names, in declaration order.
     * This does not resolve types or return any live Soot objects.
     *
     * @param sig non-null full signature with the canonical spacing described by this class
     * @return a new mutable list of type strings, empty for a no-argument method
     * @throws NullPointerException if {@code sig} is null
     * @throws IndexOutOfBoundsException if the input lacks the expected token structure
     */
    public List<String> getParametersNamesFromJimpleSignature(String sig) {
        String tmp = sig.split(" ")[2];
        String params = tmp.substring(tmp.indexOf("(") + 1, tmp.indexOf(")"));
        String[] paramsArray = params.split(",");
        List<String> parameters = new ArrayList<String>();
        String p = null;
        for (int i = 0; i < paramsArray.length; i++) {
            p = paramsArray[i];
            if (!p.isEmpty()) {
                parameters.add(p);
            }
        }
        return parameters;
    }


    /**
     * Converts a JVM object-type descriptor, for example {@code Ljava/lang/String;},
     * to a dotted class name such as {@code java.lang.String}. Despite the legacy name,
     * this does not convert method descriptors, primitive descriptors, or array descriptors.
     * It simply removes the first and last characters and replaces slashes with dots;
     * the {@code L} and {@code ;} delimiters are not validated.
     *
     * @param sig non-null object descriptor in {@code Lpackage/Class;} form
     * @return dotted class name, not a full Jimple method signature
     * @throws NullPointerException if {@code sig} is null
     * @throws IndexOutOfBoundsException if {@code sig} contains fewer than two characters
     */
    public String javaSigToSootSig(String sig) {
        return sig.substring(1, sig.length() - 1).replace("/", ".");
    }


    /**
     * Extracts the return type, method name, and parameter types from a canonical
     * full Jimple signature, for example {@code void send(int,java.lang.String)}.
     *
     * @param sig non-null full signature with the canonical spacing described by this class
     * @return sub-signature without the declaring class or enclosing angle brackets
     * @throws NullPointerException if {@code sig} is null
     * @throws IndexOutOfBoundsException if the input lacks the expected token structure
     */
    public String sigToSubSig(String sig) {
        StringBuilder sb = new StringBuilder();
        String[] split = sig.split(" ");
        sb.append(split[1]);
        sb.append(" ");
        sb.append(split[2]);
        return sb.substring(0, sb.length() - 1);
    }
}
