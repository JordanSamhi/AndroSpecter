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
 * A utility class for converting Java method signatures to Soot method signatures, and extracting various components of
 * method signatures (class name, method name, return type, and parameter names).
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class MethodSignatureConverter {

    private static MethodSignatureConverter instance;

    public MethodSignatureConverter() {
    }

    public static MethodSignatureConverter v() {
        if (instance == null) {
            instance = new MethodSignatureConverter();
        }
        return instance;
    }

    /**
     * Extracts and returns the class name from a given Jimple signature.
     * <p>
     * The method assumes the class name is the first space-separated value in the signature,
     * and it is surrounded by certain characters that are trimmed (first and last character of the string).
     *
     * @param sig The Jimple signature as a string.
     * @return The class name extracted from the Jimple signature.
     */
    public String getClassNameFromJimpleSignature(String sig) {
        String tmp = sig.split(" ")[0];
        return tmp.substring(1, tmp.length() - 1);
    }


    /**
     * Extracts and returns the method name from a given Jimple signature.
     * <p>
     * The method assumes the method name is the third space-separated value in the signature,
     * and is located before the first opening parenthesis "(".
     *
     * @param sig The Jimple signature as a string.
     * @return The method name extracted from the Jimple signature.
     */
    public String getMethodNameFromJimpleSignature(String sig) {
        String tmp = sig.split(" ")[2];
        return tmp.substring(0, tmp.indexOf("("));
    }


    /**
     * Extracts and returns the return type from a given Jimple signature.
     * <p>
     * The method assumes the return type is the second space-separated value in the signature.
     *
     * @param sig The Jimple signature as a string.
     * @return The return type extracted from the Jimple signature.
     */
    public String getReturnNameFromJimpleSignature(String sig) {
        return sig.split(" ")[1];
    }


    /**
     * Extracts and returns the list of parameter types from a given Jimple signature.
     * <p>
     * The method assumes the method signature, which includes parameter types, is the third space-separated value in the signature,
     * and is located within the parentheses "()". If multiple parameters exist, they are expected to be comma-separated.
     *
     * @param sig The Jimple signature as a string.
     * @return A list of parameter types extracted from the Jimple signature. An empty list is returned if no parameters exist.
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
     * Converts a Java signature to a Soot signature.
     *
     * @param sig the Java signature string to convert to a Soot signature string
     * @return the Soot signature string resulting from the conversion of the Java signature string
     */
    public String javaSigToSootSig(String sig) {
        return sig.substring(1, sig.length() - 1).replace("/", ".");
    }


    /**
     * Extracts the sub-signature from a given signature string.
     *
     * @param sig the signature string from which to extract the sub-signature
     * @return the sub-signature extracted from the given signature string
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