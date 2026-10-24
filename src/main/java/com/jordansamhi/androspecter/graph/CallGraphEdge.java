package com.jordansamhi.androspecter.graph;

import java.util.Objects;

/**
 * Immutable directed edge detached from Soot. Parallel graph edges may have identical
 * component values and still occur separately in a {@link CallGraphSnapshot}.
 * Record equality compares all five values, including statement text and position;
 * {@link GraphDifference} deliberately uses a different identity for cross-run comparisons.
 * All values survive scene resets and can be read across threads.
 *
 * @param source non-null source method's Jimple signature
 * @param target non-null target method's Jimple signature
 * @param callSite processed Jimple statement text, or null for an edge without a source unit
 * @param kind non-null Soot edge-kind name, such as {@code STATIC} or {@code VIRTUAL}
 * @param callSiteIndex zero-based source-unit position in the source's active Jimple body
 *                      at capture time, or -1 when the unit or its position is unavailable;
 *                      this is not a source line number or bytecode offset
 */
public record CallGraphEdge(String source, String target, String callSite, String kind, int callSiteIndex) {
    /**
     * Creates an edge from detached values without accessing Soot. Strings are opaque:
     * signatures and kinds are not parsed or validated. The position is checked only
     * against the lower bound {@code -1}; it is not checked against the statement or a body.
     *
     * @param source non-null source method signature
     * @param target non-null target method signature
     * @param callSite statement text, or null when unavailable
     * @param kind non-null edge-kind name
     * @param callSiteIndex zero-based source-unit position, or -1 when unavailable
     * @throws NullPointerException if {@code source}, {@code target}, or {@code kind} is null
     * @throws IllegalArgumentException if {@code callSiteIndex} is less than -1
     */
    public CallGraphEdge {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        if (callSiteIndex < -1) {
            throw new IllegalArgumentException("callSiteIndex must be -1 or a zero-based index");
        }
    }
}
