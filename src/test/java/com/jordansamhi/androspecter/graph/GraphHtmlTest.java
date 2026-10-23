package com.jordansamhi.androspecter.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraphHtmlTest {
    private static final String DATA_START = "<script id=\"graph-data\" type=\"application/json\">\n";

    @Test
    void retainsAllGraphDataAndParallelKindsInCompactIndexedPayload() {
        String source = "<example.Original: void source()>";
        CallGraphEdge edge = new CallGraphEdge(source, "target", "invoke target()", "STATIC", 9);
        CallGraphSnapshot graph = CallGraphSnapshot.of(UUID.randomUUID(), List.of(source, "target", "isolated"),
                List.of(edge, edge, new CallGraphEdge(source, "target", null, "VIRTUAL", -1),
                        new CallGraphEdge("target", source, "", "SPECIAL", 0)));
        String html = GraphHtml.toHtml(graph);
        JsonObject payload = JsonParser.parseString(data(html)).getAsJsonObject();
        assertEquals(1, payload.get("viewerVersion").getAsInt());
        assertEquals(graph.analysisId().toString(), payload.get("analysisId").getAsString());
        assertEquals(3, payload.getAsJsonArray("methods").size());
        assertEquals(3, payload.getAsJsonArray("kinds").size());
        assertEquals(4, payload.getAsJsonArray("edges").size());
        for (JsonElement element : payload.getAsJsonArray("edges")) {
            JsonArray tuple = element.getAsJsonArray();
            assertEquals(5, tuple.size());
            assertTrue(tuple.get(0).getAsJsonPrimitive().isNumber());
            assertTrue(tuple.get(1).getAsJsonPrimitive().isNumber());
            assertTrue(tuple.get(2).getAsJsonPrimitive().isNumber());
        }
        CallGraphSnapshot restored = restore(payload);
        assertEquals(graph.methods(), restored.methods());
        assertEquals(graph.edges(), restored.edges());
        assertEquals(html, GraphHtml.toHtml(graph));
    }

    @Test
    void untrustedTextCannotTerminateJsonScriptOrBecomeHtml() {
        String source = "</script><script>globalThis.attacked=true</script><!--\u2028\u2029";
        String target = "</ScRiPt><img src=x onerror=alert(1)>&\"'\0";
        String kind = "</style><svg onload=alert(2)>";
        String statement = "<iframe src=javascript:alert(3)> & \" \\ \r\n\t\0";
        CallGraphSnapshot graph = CallGraphSnapshot.of(UUID.randomUUID(), List.of(source, target, "__proto__"),
                List.of(new CallGraphEdge(source, target, statement, kind, 17)));
        String html = GraphHtml.toHtml(graph);
        String json = data(html);
        assertFalse(json.contains("<"));
        assertFalse(json.contains(">"));
        assertFalse(json.contains("&"));
        assertFalse(json.contains("\u2028"));
        assertFalse(json.contains("\u2029"));
        assertFalse(json.contains("\0"));
        assertTrue(json.contains("\\u003c/script\\u003e"));
        assertFalse(html.contains("<img src=x"));
        assertFalse(html.contains("<svg onload="));
        assertFalse(html.contains("<iframe src="));
        CallGraphSnapshot restored = restore(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(graph.methods(), restored.methods());
        assertEquals(graph.edges(), restored.edges());
    }

    @Test
    void emptyGraphStillContainsStandaloneViewerAndExplicitDisplayLimits() {
        String html = GraphHtml.toHtml(CallGraphSnapshot.of(UUID.randomUUID(), List.of()));
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.endsWith("</html>\n"));
        assertTrue(html.contains("connect-src 'none'"));
        assertTrue(html.contains("60 methods and 180"));
        assertTrue(html.contains("Callers and callees"));
        assertTrue(html.contains("id=\"method-search\""));
        assertTrue(html.contains("id=\"graph-svg\""));
        assertFalse(html.contains("innerHTML"));
        assertFalse(html.matches("(?s).*<(?:script|link)[^>]+(?:src|href)=.*"));
        assertFalse(html.contains("fetch("));
        assertFalse(html.contains("XMLHttpRequest"));
        JsonObject payload = JsonParser.parseString(data(html)).getAsJsonObject();
        assertTrue(payload.getAsJsonArray("methods").isEmpty());
        assertTrue(payload.getAsJsonArray("edges").isEmpty());
    }

    @Test
    void callerOwnsWriterAndIoFailuresPropagate() throws IOException {
        CallGraphSnapshot graph = CallGraphSnapshot.of(UUID.randomUUID(), List.of("isolated"), List.of());
        class TrackedWriter extends StringWriter {
            boolean closed;
            boolean flushed;
            @Override public void close() { closed = true; }
            @Override public void flush() { flushed = true; }
        }
        TrackedWriter destination = new TrackedWriter();
        GraphHtml.write(graph, destination);
        assertEquals(GraphHtml.toHtml(graph), destination.toString());
        assertFalse(destination.closed);
        assertFalse(destination.flushed);
        IOException failure = new IOException("failed destination");
        Writer broken = new Writer() {
            @Override public void write(char[] buffer, int offset, int length) throws IOException { throw failure; }
            @Override public void close() {}
            @Override public void flush() {}
        };
        assertSame(failure, assertThrows(IOException.class, () -> GraphHtml.write(graph, broken)));
        StringWriter untouched = new StringWriter();
        assertThrows(NullPointerException.class, () -> GraphHtml.write(null, untouched));
        assertEquals("", untouched.toString());
        assertThrows(NullPointerException.class, () -> GraphHtml.write(graph, null));
        assertThrows(NullPointerException.class, () -> GraphHtml.toHtml(null));
    }

    private static String data(String html) {
        int start = html.indexOf(DATA_START);
        assertTrue(start >= 0);
        int end = html.indexOf("\n</script>", start);
        assertTrue(end > start);
        return html.substring(start + DATA_START.length(), end);
    }

    private static CallGraphSnapshot restore(JsonObject payload) {
        List<String> methods = new ArrayList<>();
        for (JsonElement method : payload.getAsJsonArray("methods")) methods.add(method.getAsString());
        JsonArray kinds = payload.getAsJsonArray("kinds");
        List<CallGraphEdge> edges = new ArrayList<>();
        for (JsonElement element : payload.getAsJsonArray("edges")) {
            JsonArray edge = element.getAsJsonArray();
            edges.add(new CallGraphEdge(methods.get(edge.get(0).getAsInt()), methods.get(edge.get(1).getAsInt()),
                    edge.get(3).isJsonNull() ? null : edge.get(3).getAsString(),
                    kinds.get(edge.get(2).getAsInt()).getAsString(), edge.get(4).getAsInt()));
        }
        return CallGraphSnapshot.of(UUID.fromString(payload.get("analysisId").getAsString()), methods, edges);
    }
}
