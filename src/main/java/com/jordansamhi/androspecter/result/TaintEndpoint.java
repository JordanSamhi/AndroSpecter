package com.jordansamhi.androspecter.result;

import java.util.Objects;

/**
 * A detached source or sink, including FlowDroid's textual description.
 * methodSignature identifies the owning method; invokedMethodSignature identifies the called
 * source/sink API when the statement contains an invocation. Unavailable values are null.
 */
public record TaintEndpoint(String description, String methodSignature, String statement,
                            String invokedMethodSignature, String accessPath) {
    public TaintEndpoint {
        Objects.requireNonNull(description, "description");
    }

    /** Constructs an endpoint when invoked-method and access-path metadata are unavailable. */
    public TaintEndpoint(String description, String methodSignature, String statement) {
        this(description, methodSignature, statement, null, null);
    }
}
