package com.jordansamhi.androspecter.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GraphTraversalTest {
    private CallGraphEdge edge(String source, String target, String kind) {
        return new CallGraphEdge(source, target, null, kind, -1);
    }

    private CallGraphSnapshot graph() {
        return CallGraphSnapshot.of(UUID.randomUUID(), List.of("I", "D", "C", "B", "A"), List.of(
                edge("A", "C", "STATIC"), edge("C", "D", "STATIC"),
                edge("A", "B", "VIRTUAL"), edge("B", "D", "STATIC"),
                edge("D", "A", "STATIC"), edge("A", "B", "STATIC"), edge("A", "B", "STATIC")));
    }

    @Test
    void reachabilityTerminatesOnCyclesAndDistinguishesUnknownFromIsolatedNodes() {
        CallGraphSnapshot graph = graph();
        assertEquals(List.of("A", "B", "C", "D"), new ArrayList<>(graph.reachableFrom("A")));
        assertEquals(Set.of("A", "B", "C", "D"), graph.reachableTo("D"));
        assertEquals(Set.of("I"), graph.reachableFrom("I"));
        assertEquals(Set.of("I"), graph.reachableTo("I"));
        assertTrue(graph.reachableFrom("missing").isEmpty());
        assertTrue(graph.reachableTo("missing").isEmpty());
        assertTrue(graph.canReach("A", "D"));
        assertTrue(graph.canReach("D", "C"));
        assertTrue(graph.canReach("I", "I"));
        assertFalse(graph.canReach("I", "A"));
        assertFalse(graph.canReach("A", "I"));
        assertFalse(graph.canReach("missing", "missing"));
        assertThrows(UnsupportedOperationException.class, () -> graph.reachableFrom("A").clear());
    }

    @Test
    void shortestPathHasDeterministicTiesAndDocumentedZeroEdgeBehavior() {
        CallGraphSnapshot graph = graph();
        assertEquals(List.of("A", "B", "D"), graph.shortestPath("A", "D"));
        assertEquals(List.of("I"), graph.shortestPath("I", "I"));
        assertEquals(List.of("A"), graph.shortestPath("A", "A"));
        assertTrue(graph.shortestPath("A", "I").isEmpty());
        assertTrue(graph.shortestPath("A", "missing").isEmpty());
        assertTrue(graph.shortestPath("missing", "A").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> graph.shortestPath("A", "D").clear());

        List<CallGraphEdge> reversed = new ArrayList<>(graph.edges());
        Collections.reverse(reversed);
        CallGraphSnapshot reordered = CallGraphSnapshot.of(graph.analysisId(), graph.methods(), reversed);
        assertEquals(graph.shortestPath("A", "D"), reordered.shortestPath("A", "D"));
    }

    @Test
    void filtersRetainSelectedIsolatedNodesAndParallelEdgesWithoutChangingOriginal() {
        CallGraphSnapshot original = graph();
        AtomicInteger nodeChecks = new AtomicInteger();
        AtomicInteger edgeChecks = new AtomicInteger();
        CallGraphSnapshot filtered = original.subgraph(method -> {
            nodeChecks.incrementAndGet();
            return !method.equals("C");
        }, edge -> {
            edgeChecks.incrementAndGet();
            assertFalse(edge.source().equals("C") || edge.target().equals("C"));
            return edge.kind().equals("STATIC");
        });
        assertEquals(5, nodeChecks.get());
        assertEquals(5, edgeChecks.get());
        assertEquals(Set.of("A", "B", "D", "I"), filtered.methods());
        assertEquals(4, filtered.edges().size());
        assertEquals(2, filtered.outgoingEdges("A").size());
        assertEquals(Set.of("I"), filtered.reachableFrom("I"));
        assertEquals(original.analysisId(), filtered.analysisId());
        assertEquals(7, original.edges().size());

        CallGraphSnapshot isolated = original.subgraph("A"::equals);
        assertEquals(Set.of("A"), isolated.methods());
        assertTrue(isolated.edges().isEmpty());
        assertTrue(original.subgraph(method -> false).methods().isEmpty());
        assertEquals(original.methods(), original.subgraph(method -> true, edge -> false).methods());
    }

    @Test
    void factoriesCopyInputAndRejectDanglingEdges() {
        List<String> methods = new ArrayList<>(List.of("A", "B", "I", "A"));
        List<CallGraphEdge> edges = new ArrayList<>(List.of(edge("A", "B", "STATIC")));
        CallGraphSnapshot graph = CallGraphSnapshot.of(UUID.randomUUID(), methods, edges);
        methods.clear();
        edges.clear();
        assertEquals(Set.of("A", "B", "I"), graph.methods());
        assertEquals(1, graph.edges().size());
        assertThrows(IllegalArgumentException.class, () -> CallGraphSnapshot.of(UUID.randomUUID(),
                List.of("A"), List.of(edge("A", "B", "STATIC"))));
        assertThrows(NullPointerException.class, () -> CallGraphSnapshot.of(null, List.of()));
        assertThrows(NullPointerException.class, () -> graph.reachableFrom(null));
        assertThrows(NullPointerException.class, () -> graph.reachableTo(null));
        assertThrows(NullPointerException.class, () -> graph.shortestPath(null, "A"));
        assertThrows(NullPointerException.class, () -> graph.shortestPath("A", null));
        assertThrows(NullPointerException.class, () -> graph.subgraph(null));
        assertThrows(NullPointerException.class, () -> graph.subgraph(method -> true, null));
    }
}
