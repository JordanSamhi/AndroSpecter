package com.jordansamhi.androspecter.graph;

import java.util.Objects;

/**
 * A detached directed edge. callSiteIndex is the zero-based position in the source's
 * active Jimple body, or -1 when unavailable. Synthetic edges may have a null callSite.
 */
public record CallGraphEdge(String source, String target, String callSite, String kind, int callSiteIndex) {
    public CallGraphEdge {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        if (callSiteIndex < -1) {
            throw new IllegalArgumentException("callSiteIndex must be -1 or a zero-based index");
        }
    }
}
