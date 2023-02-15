package com.jordansamhi.utils;

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

import java.util.ArrayList;
import java.util.List;

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
     * Extracts the class name from a given method signature string.
     *
     * @param sig the method signature string from which to extract the class name
     * @return the class name extracted from the given method signature string
     */
    public String getClassNameFromSignature(String sig) {
        String tmp = sig.split(" ")[0];
        return tmp.substring(1, tmp.length() - 1);
    }

    /**
     * Extracts the method name from a given method signature string.
     *
     * @param sig the method signature string from which to extract the method name
     * @return the method name extracted from the given method signature string
     */
    public String getMethodNameFromSignature(String sig) {
        String tmp = sig.split(" ")[2];
        return tmp.substring(0, tmp.indexOf("("));
    }


    /**
     * Extracts the return type name from a given method signature string.
     *
     * @param sig the method signature string from which to extract the return type name
     * @return the return type name extracted from the given method signature string
     */
    public String getReturnNameFromSignature(String sig) {
        return sig.split(" ")[1];
    }


    /**
     * Extracts the names of the parameters from a given method signature string.
     *
     * @param sig the method signature string from which to extract the parameter names
     * @return a list of parameter names extracted from the given method signature string
     */
    public List<String> getParametersNamesFromSignature(String sig) {
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