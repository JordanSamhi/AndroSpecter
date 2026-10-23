package com.jordansamhi.androspecter.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraphDotTest {
    @Test
    void exportsEscapedIdentifiersAttributesIsolatedNodesAndEveryParallelEdge() {
        String source = "A\"\\\n";
        CallGraphEdge edge = new CallGraphEdge(source, "B", "say(\"x\\y\")\r\nnext", "S\"T", 4);
        CallGraphSnapshot graph = CallGraphSnapshot.of(UUID.randomUUID(), Set.of(source, "B", "I"),
                List.of(edge, edge, new CallGraphEdge("B", source, null, "STATIC", -1)));
        String dot = GraphDot.toDot(graph);
        assertTrue(dot.startsWith("digraph CallGraph {\n"));
        assertTrue(dot.contains("  \"I\";\n"));
        assertTrue(dot.contains("\"A\\\"\\\\\\n\""));
        assertTrue(dot.contains("label=\"S\\\"T\""));
        assertTrue(dot.contains("tooltip=\"say(\\\"x\\\\y\\\")\\r\\nnext\""));
        assertTrue(dot.contains("callSiteIndex=\"4\""));
        assertEquals(3, dot.lines().filter(line -> line.contains(" -> ")).count());
        assertEquals(2, dot.lines().filter(line -> line.contains("tooltip=")).count());
        assertEquals(dot, GraphDot.toDot(graph));
    }

    @Test
    void writerRemainsOwnedByCallerAndIoFailuresPropagate() throws IOException {
        CallGraphSnapshot graph = CallGraphSnapshot.of(UUID.randomUUID(), Set.of("I"), List.of());
        class TrackedWriter extends StringWriter {
            boolean closed;
            boolean flushed;
            @Override public void close() { closed = true; }
            @Override public void flush() { flushed = true; }
        }
        TrackedWriter destination = new TrackedWriter();
        GraphDot.write(graph, destination);
        assertFalse(destination.closed);
        assertFalse(destination.flushed);
        assertEquals("digraph CallGraph {\n  \"I\";\n}\n", destination.toString());

        IOException failure = new IOException("failed destination");
        Writer broken = new Writer() {
            @Override public void write(char[] buffer, int offset, int length) throws IOException { throw failure; }
            @Override public void flush() {}
            @Override public void close() {}
        };
        assertSame(failure, assertThrows(IOException.class, () -> GraphDot.write(graph, broken)));
    }

    @Test
    void rejectsNulInAnyIdentifierOrAttributeBeforeWritingOutput() {
        List<CallGraphSnapshot> invalid = List.of(
                CallGraphSnapshot.of(UUID.randomUUID(), Set.of("isolated\0node"), List.of()),
                CallGraphSnapshot.of(UUID.randomUUID(), List.of(new CallGraphEdge("source\0", "B", null, "STATIC", -1))),
                CallGraphSnapshot.of(UUID.randomUUID(), List.of(new CallGraphEdge("A", "target\0", null, "STATIC", -1))),
                CallGraphSnapshot.of(UUID.randomUUID(), List.of(new CallGraphEdge("A", "B", null, "kind\0", -1))),
                CallGraphSnapshot.of(UUID.randomUUID(), List.of(new CallGraphEdge("A", "B", "statement\0", "STATIC", -1))));
        for (CallGraphSnapshot graph : invalid) {
            StringWriter output = new StringWriter();
            assertThrows(IllegalArgumentException.class, () -> GraphDot.write(graph, output));
            assertEquals("", output.toString());
            assertThrows(IllegalArgumentException.class, () -> GraphDot.toDot(graph));
            assertEquals(graph.edges(), GraphJson.fromJson(GraphJson.toJson(graph)).edges());
        }
    }
}
