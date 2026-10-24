package com.jordansamhi.androspecter.result;

import java.util.List;
import java.util.Objects;

/**
 * Immutable source-to-sink finding with an optional reconstructed statement path.
 * A finding remains meaningful when its detailed path is unavailable. A present but
 * empty path differs from an unavailable path; consult {@code pathAvailable}.
 * The value and all nested data remain usable across threads and after Soot resets.
 * A reconstructed path is backend evidence of a modeled flow, not a runtime trace;
 * this record does not enforce that its first or last step matches the endpoints.
 *
 * @param source non-null detached source endpoint
 * @param sink non-null detached sink endpoint
 * @param path non-null steps in backend reconstruction order, with no null elements;
 *             copied to an immutable list and empty when the path is unavailable
 * @param pathAvailable whether the backend supplied a path array, including an empty array;
 *                      this does not by itself establish analysis completeness
 */
public record TaintFinding(TaintEndpoint source, TaintEndpoint sink,
                           List<TaintPathStep> path, boolean pathAvailable) {
    /**
     * Creates a finding and defensively copies its reconstructed path, preserving order and
     * duplicates. Inputs must remain stable while copied. The constructor checks only nulls
     * and the unavailable-path invariant; it does not validate flow feasibility or completeness.
     *
     * @param source non-null source endpoint
     * @param sink non-null sink endpoint
     * @param path non-null ordered path steps; null elements are not allowed
     * @param pathAvailable whether a reconstructed path is available
     * @throws NullPointerException if an endpoint, the path, or a path element is null
     * @throws IllegalArgumentException if {@code pathAvailable} is false but {@code path} is nonempty
     */
    public TaintFinding {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sink, "sink");
        path = List.copyOf(path);
        if (!pathAvailable && !path.isEmpty()) {
            throw new IllegalArgumentException("An unavailable path cannot contain steps");
        }
    }
}
