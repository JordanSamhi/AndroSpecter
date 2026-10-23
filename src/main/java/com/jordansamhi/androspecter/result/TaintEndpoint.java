package com.jordansamhi.androspecter.result;

import java.util.Objects;

/**
 * Immutable source or sink metadata detached from FlowDroid and Soot objects.
 * For filtering by the API called at an endpoint, use {@code invokedMethodSignature};
 * {@code methodSignature} identifies the enclosing method instead. Statement and
 * access-path strings describe FlowDroid's processed model and remain valid after reset.
 *
 * @param description non-null textual description supplied by FlowDroid, used by the legacy formatter
 * @param methodSignature enclosing method's Jimple signature, or null when its owner is unavailable
 * @param statement processed Jimple statement text, or null when no statement is available
 * @param invokedMethodSignature invoked method reference's Jimple signature, or null when the
 *                               endpoint has no invocation or this metadata is unavailable
 * @param accessPath textual tainted access path, or null when unavailable
 */
public record TaintEndpoint(String description, String methodSignature, String statement,
                            String invokedMethodSignature, String accessPath) {
    /**
     * Creates a detached endpoint without resolving or retaining any live analysis objects.
     *
     * @param description non-null textual endpoint description
     * @param methodSignature enclosing method signature, or null when unavailable
     * @param statement processed statement text, or null when unavailable
     * @param invokedMethodSignature invoked method reference signature, or null when unavailable
     * @param accessPath textual access path, or null when unavailable
     * @throws NullPointerException if {@code description} is null
     */
    public TaintEndpoint {
        Objects.requireNonNull(description, "description");
    }

    /**
     * Creates an endpoint with null invoked-method and access-path metadata.
     *
     * @param description non-null textual endpoint description
     * @param methodSignature enclosing method signature, or null when unavailable
     * @param statement processed statement text, or null when unavailable
     * @throws NullPointerException if {@code description} is null
     */
    public TaintEndpoint(String description, String methodSignature, String statement) {
        this(description, methodSignature, statement, null, null);
    }
}
