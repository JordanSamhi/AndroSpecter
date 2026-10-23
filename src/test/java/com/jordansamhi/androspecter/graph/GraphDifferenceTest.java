package com.jordansamhi.androspecter.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraphDifferenceTest {
    private CallGraphEdge edge(String kind, String statement, int index) {
        return new CallGraphEdge("A", "B", statement, kind, index);
    }

    @Test
    void differencesPreserveMultiplicityButIgnoreUnstableCallSiteMetadata() {
        CallGraphSnapshot before = CallGraphSnapshot.of(UUID.randomUUID(), List.of("A", "B", "old-isolated"),
                List.of(edge("STATIC", "old call", 1), edge("STATIC", "old call", 2),
                        edge("VIRTUAL", "old dispatch", 3), edge("VIRTUAL", "old dispatch", 4)));
        CallGraphSnapshot after = CallGraphSnapshot.of(UUID.randomUUID(), List.of("A", "B", "new-isolated"),
                List.of(edge("STATIC", "renamed local", 19), edge("STATIC", null, -1),
                        edge("STATIC", "additional site", 20), edge("VIRTUAL", "new dispatch", 100)));

        GraphDifference changes = GraphDifference.between(before, after);
        assertFalse(changes.isEmpty());
        assertEquals(Set.of("new-isolated"), changes.addedMethods());
        assertEquals(Set.of("old-isolated"), changes.removedMethods());
        assertEquals(Map.of(new GraphDifference.EdgeIdentity("A", "B", "STATIC"), 1), changes.addedEdges());
        assertEquals(Map.of(new GraphDifference.EdgeIdentity("A", "B", "VIRTUAL"), 1), changes.removedEdges());
        assertThrows(UnsupportedOperationException.class, () -> changes.addedMethods().clear());
        assertThrows(UnsupportedOperationException.class, () -> changes.removedEdges().clear());

        GraphDifference reverse = GraphDifference.between(after, before);
        assertEquals(changes.addedEdges(), reverse.removedEdges());
        assertEquals(changes.removedEdges(), reverse.addedEdges());
        assertEquals(changes.addedMethods(), reverse.removedMethods());
    }

    @Test
    void equivalentGraphsCanHaveDifferentIdsAndCallSites() {
        CallGraphSnapshot before = CallGraphSnapshot.of(UUID.randomUUID(), List.of(edge("STATIC", "x", 0)));
        CallGraphSnapshot after = CallGraphSnapshot.of(UUID.randomUUID(), List.of(edge("STATIC", "y", 42)));
        assertTrue(GraphDifference.between(before, after).isEmpty());
        assertTrue(GraphDifference.between(before, before).isEmpty());
        assertThrows(NullPointerException.class, () -> GraphDifference.between(before, null));
    }

    @Test
    void isolatedNodeChangesAndKindChangesAreVisible() {
        CallGraphSnapshot empty = CallGraphSnapshot.of(UUID.randomUUID(), List.of());
        CallGraphSnapshot isolated = CallGraphSnapshot.of(UUID.randomUUID(), Set.of("I"), List.of());
        GraphDifference isolatedChange = GraphDifference.between(empty, isolated);
        assertFalse(isolatedChange.isEmpty());
        assertEquals(Set.of("I"), isolatedChange.addedMethods());
        assertTrue(isolatedChange.addedEdges().isEmpty());

        CallGraphSnapshot before = CallGraphSnapshot.of(UUID.randomUUID(), List.of(edge("STATIC", "x", 0)));
        CallGraphSnapshot after = CallGraphSnapshot.of(UUID.randomUUID(), List.of(edge("VIRTUAL", "x", 0)));
        GraphDifference kindChange = GraphDifference.between(before, after);
        assertEquals(1, kindChange.addedEdges().size());
        assertEquals(1, kindChange.removedEdges().size());
        assertTrue(kindChange.addedMethods().isEmpty());
    }
}
