package com.jordansamhi.androspecter.graph;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable difference between two detached graphs. Edge identity is the source signature,
 * target signature, and kind; statement text and capture-time positions are ignored. Edge
 * multiplicity is compared, so adding a second equivalent call edge is still a change.
 * Analysis IDs are not compared. This describes structural differences, not their cause.
 * No methods are resolved and no Soot state is accessed. The result and its collections
 * can be read across threads and outlive both originating analysis sessions. The caller
 * is responsible for choosing comparable algorithms, scopes, and analysis configurations.
 */
public final class GraphDifference {
    private static final Comparator<EdgeIdentity> EDGE_ORDER = Comparator.comparing(EdgeIdentity::source)
            .thenComparing(EdgeIdentity::target).thenComparing(EdgeIdentity::kind);

    private final Set<String> addedMethods;
    private final Set<String> removedMethods;
    private final Map<EdgeIdentity, Integer> addedEdges;
    private final Map<EdgeIdentity, Integer> removedEdges;

    private GraphDifference(Set<String> addedMethods, Set<String> removedMethods,
                            Map<EdgeIdentity, Integer> addedEdges, Map<EdgeIdentity, Integer> removedEdges) {
        this.addedMethods = Collections.unmodifiableSet(addedMethods);
        this.removedMethods = Collections.unmodifiableSet(removedMethods);
        this.addedEdges = Collections.unmodifiableMap(addedEdges);
        this.removedEdges = Collections.unmodifiableMap(removedEdges);
    }

    /**
     * Compares node membership and edge counts in two snapshots. Each positive added or
     * removed count is the difference in multiplicity for that identity, not its total in
     * either graph. Isolated nodes participate in the node comparison. Inputs are unchanged.
     * A changed edge kind appears as a removal of the old identity and an addition of the
     * new identity. Changes only to statement text, position, or analysis UUID do not count.
     * <pre>{@code
     * GraphDifference changes = GraphDifference.between(before, after);
     * changes.addedEdges().forEach((edge, count) ->
     *         System.out.println(edge.source() + " -> " + edge.target() + ": +" + count));
     * }</pre>
     *
     * @param before non-null baseline graph
     * @param after non-null graph to compare against the baseline
     * @return detached difference, ordered by method signature and source/target/kind
     * @throws NullPointerException if either graph is null
     */
    public static GraphDifference between(CallGraphSnapshot before, CallGraphSnapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Set<String> addedMethods = new TreeSet<>(after.methods());
        addedMethods.removeAll(before.methods());
        Set<String> removedMethods = new TreeSet<>(before.methods());
        removedMethods.removeAll(after.methods());

        Map<EdgeIdentity, Integer> beforeCounts = count(before);
        Map<EdgeIdentity, Integer> afterCounts = count(after);
        Map<EdgeIdentity, Integer> addedEdges = new TreeMap<>(EDGE_ORDER);
        Map<EdgeIdentity, Integer> removedEdges = new TreeMap<>(EDGE_ORDER);
        Set<EdgeIdentity> identities = new TreeSet<>(EDGE_ORDER);
        identities.addAll(beforeCounts.keySet());
        identities.addAll(afterCounts.keySet());
        for (EdgeIdentity identity : identities) {
            int change = afterCounts.getOrDefault(identity, 0) - beforeCounts.getOrDefault(identity, 0);
            if (change > 0) {
                addedEdges.put(identity, change);
            } else if (change < 0) {
                removedEdges.put(identity, -change);
            }
        }
        return new GraphDifference(addedMethods, removedMethods, addedEdges, removedEdges);
    }

    private static Map<EdgeIdentity, Integer> count(CallGraphSnapshot graph) {
        Map<EdgeIdentity, Integer> counts = new TreeMap<>(EDGE_ORDER);
        for (CallGraphEdge edge : graph.edges()) {
            counts.merge(new EdgeIdentity(edge.source(), edge.target(), edge.kind()), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Returns nodes present only in the later graph, including isolated nodes.
     *
     * @return immutable lexicographically ordered added method signatures
     */
    public Set<String> addedMethods() {
        return addedMethods;
    }

    /**
     * Returns nodes present only in the baseline graph, including isolated nodes.
     *
     * @return immutable lexicographically ordered removed method signatures
     */
    public Set<String> removedMethods() {
        return removedMethods;
    }

    /**
     * Returns additional edge occurrences grouped by source, target, and kind. Changes
     * to statement text or position alone do not contribute an added occurrence.
     *
     * @return immutable map ordered lexicographically by source, target, and kind, with positive added counts
     */
    public Map<EdgeIdentity, Integer> addedEdges() {
        return addedEdges;
    }

    /**
     * Returns removed edge occurrences grouped by source, target, and kind. Counts describe
     * how many occurrences disappeared, not how many remain in the later graph.
     *
     * @return immutable map ordered lexicographically by source, target, and kind, with positive removed counts
     */
    public Map<EdgeIdentity, Integer> removedEdges() {
        return removedEdges;
    }

    /**
     * Tests whether node membership and every edge-identity count are unchanged.
     *
     * @return true when all four change collections are empty, regardless of analysis IDs or call-site metadata
     */
    public boolean isEmpty() {
        return addedMethods.isEmpty() && removedMethods.isEmpty() && addedEdges.isEmpty() && removedEdges.isEmpty();
    }

    /**
     * Edge identity used for structural comparisons across analyses or transformations.
     *
     * @param source non-null source method signature
     * @param target non-null target method signature
     * @param kind non-null edge kind
     */
    public record EdgeIdentity(String source, String target, String kind) {
        /**
         * Creates an identity without resolving methods or retaining Soot objects.
         * Values are compared exactly as strings; no signature or kind syntax is validated.
         *
         * @param source non-null source method signature
         * @param target non-null target method signature
         * @param kind non-null edge kind
         * @throws NullPointerException if any component is null
         */
        public EdgeIdentity {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
