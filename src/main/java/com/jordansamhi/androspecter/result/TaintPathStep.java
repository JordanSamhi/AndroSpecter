package com.jordansamhi.androspecter.result;

import java.util.Objects;

/**
 * Immutable reconstructed path statement, detached from the analysis scene.
 * The statement text describes processed Jimple rather than original source code.
 *
 * @param methodSignature enclosing method's Jimple signature, or null when the owner is unavailable
 * @param statement non-null processed Jimple statement text
 */
public record TaintPathStep(String methodSignature, String statement) {
    /**
     * Creates a path step that remains usable after Soot is reset.
     *
     * @param methodSignature enclosing method signature, or null when unavailable
     * @param statement non-null processed statement text
     * @throws NullPointerException if {@code statement} is null
     */
    public TaintPathStep {
        Objects.requireNonNull(statement, "statement");
    }
}
