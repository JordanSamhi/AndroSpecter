package com.jordansamhi.androspecter.result;

import java.util.Objects;

/**
 * A detached path statement. The owning method signature is null when unavailable.
 * The statement text describes processed Jimple, not source code.
 */
public record TaintPathStep(String methodSignature, String statement) {
    public TaintPathStep {
        Objects.requireNonNull(statement, "statement");
    }
}
