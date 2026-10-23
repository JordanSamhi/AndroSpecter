package com.jordansamhi.androspecter.result;

import java.util.List;
import java.util.Objects;

/** A detached source-to-sink finding and its optional reconstructed statement path. */
public record TaintFinding(TaintEndpoint source, TaintEndpoint sink,
                           List<TaintPathStep> path, boolean pathAvailable) {
    public TaintFinding {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sink, "sink");
        path = List.copyOf(path);
        if (!pathAvailable && !path.isEmpty()) {
            throw new IllegalArgumentException("An unavailable path cannot contain steps");
        }
    }
}
