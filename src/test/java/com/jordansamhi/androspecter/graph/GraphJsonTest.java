package com.jordansamhi.androspecter.graph;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraphJsonTest {
    private String document() {
        return """
                {"schemaVersion":1,"analysisId":"12345678-1234-1234-1234-123456789abc",
                 "methods":["A","B","I"],
                 "edges":[{"source":"A","target":"B","kind":"STATIC",
                           "callSite":null,"callSiteIndex":-1}]}
                """;
    }

    @Test
    void roundTripPreservesIdentityMetadataParallelEdgesIsolatedNodesAndIndexes() {
        String source = "<example.Quote: void say(\"\\\n雪)>";
        CallGraphEdge edge = new CallGraphEdge(source, "B", "print(\"a\\b\");\r\nnext\t雪", "STATIC", 7);
        CallGraphSnapshot original = CallGraphSnapshot.of(UUID.randomUUID(), Set.of(source, "B", "I"),
                List.of(edge, edge, new CallGraphEdge("B", source, null, "GENERIC_FAKE", -1)));

        String json = GraphJson.toJson(original);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
        assertTrue(json.contains("\\n"));
        CallGraphSnapshot restored = GraphJson.fromJson(json);
        assertEquals(original.analysisId(), restored.analysisId());
        assertEquals(original.methods(), restored.methods());
        assertEquals(original.edges(), restored.edges());
        assertEquals(2, restored.outgoingEdges(source).size());
        assertEquals(List.of(source, "B"), restored.shortestPath(source, "B"));
        assertEquals(Set.of("I"), restored.reachableFrom("I"));
        assertEquals(json, GraphJson.toJson(restored));
    }

    @Test
    void acceptsFieldReorderingAndUnknownExtraFields() {
        JsonObject data = JsonParser.parseString(document()).getAsJsonObject();
        data.add("futureMetadata", JsonParser.parseString("{\"values\":[true,null,12]}"));
        data.getAsJsonArray("edges").get(0).getAsJsonObject().addProperty("futureEdgeMetadata", "ignored");
        JsonObject reordered = new JsonObject();
        reordered.add("edges", data.get("edges"));
        reordered.add("methods", data.get("methods"));
        reordered.add("futureMetadata", data.get("futureMetadata"));
        reordered.add("analysisId", data.get("analysisId"));
        reordered.add("schemaVersion", data.get("schemaVersion"));
        assertEquals(List.of("A", "B"), GraphJson.fromJson(reordered.toString()).shortestPath("A", "B"));
    }

    @Test
    void rejectsMissingNullAndWronglyTypedRequiredFields() {
        for (String field : List.of("schemaVersion", "analysisId", "methods", "edges")) {
            JsonObject missing = JsonParser.parseString(document()).getAsJsonObject();
            missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(missing.toString()), field);
            JsonObject nullValue = JsonParser.parseString(document()).getAsJsonObject();
            nullValue.add(field, JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(nullValue.toString()), field);
        }
        for (String field : List.of("source", "target", "kind", "callSite", "callSiteIndex")) {
            JsonObject missing = JsonParser.parseString(document()).getAsJsonObject();
            missing.getAsJsonArray("edges").get(0).getAsJsonObject().remove(field);
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(missing.toString()), field);
            if (!field.equals("callSite")) {
                JsonObject nullValue = JsonParser.parseString(document()).getAsJsonObject();
                nullValue.getAsJsonArray("edges").get(0).getAsJsonObject().add(field, JsonNull.INSTANCE);
                assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(nullValue.toString()), field);
            }
        }
        for (String invalid : List.of(
                document().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                document().replace("\"methods\":[\"A\",\"B\",\"I\"]", "\"methods\":{}"),
                document().replace("\"source\":\"A\"", "\"source\":12"),
                document().replace("\"callSite\":null", "\"callSite\":false"),
                document().replace("\"callSiteIndex\":-1", "\"callSiteIndex\":\"0\""))) {
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(invalid));
        }
    }

    @Test
    void rejectsMalformedDocumentsVersionsAndInconsistentGraphs() {
        for (String invalid : List.of("", "null", "[]", "{}", document().substring(0, 40),
                document() + "{}", document().replace("\"schemaVersion\"", "schemaVersion"),
                document().replace("{\"schemaVersion\"", "{/*comment*/\"schemaVersion\""),
                document().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                document().replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"),
                document().replace("\"schemaVersion\":1", "\"schemaVersion\":2147483648"),
                document().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                document().replace("12345678-1234-1234-1234-123456789abc", "invalid"),
                document().replace("12345678-1234-1234-1234-123456789abc", "1-1-1-1-1"),
                document().replace("\"target\":\"B\"", "\"target\":\"missing\""),
                document().replace("\"callSiteIndex\":-1", "\"callSiteIndex\":-2"),
                document().replace("\"callSiteIndex\":-1", "\"callSiteIndex\":0.5"),
                document().replace("\"source\":\"A\"", "\"source\":\"A\",\"source\":\"A\""))) {
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(invalid), invalid);
        }
    }

    @Test
    void rejectsRawControlCharactersAndNonJsonLiteralsEvenInIgnoredFields() {
        for (String control : List.of("\n", "\t", "\0")) {
            String invalid = document().replace("\"callSite\":null", "\"callSite\":\"raw" + control + "value\"");
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(invalid));
            String ignored = document().replace("{\"schemaVersion\"",
                    "{\"extra\":\"raw" + control + "value\",\"schemaVersion\"");
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(ignored));
            String nested = document().replace("\"kind\":", "\"extra\":[{\"value\":\"raw"
                    + control + "value\"}],\"kind\":");
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(nested));
        }
        for (String literal : List.of("TRUE", "FALSE", "NULL")) {
            String invalid = document().replace("{\"schemaVersion\"",
                    "{\"extra\":" + literal + ",\"schemaVersion\"");
            assertThrows(IllegalArgumentException.class, () -> GraphJson.fromJson(invalid));
        }
        String escaped = document().replace("\"callSite\":null", "\"callSite\":\"escaped\\n\\t\\u0000\"");
        assertEquals("escaped\n\t\0", GraphJson.fromJson(escaped).edges().get(0).callSite());
    }

    @Test
    void leavesCallerStreamsOpenAndPropagatesIoFailures() throws IOException {
        class TrackedReader extends StringReader {
            boolean closed;
            TrackedReader(String text) { super(text); }
            @Override public void close() { closed = true; super.close(); }
        }
        class TrackedWriter extends StringWriter {
            boolean closed;
            boolean flushed;
            @Override public void close() { closed = true; }
            @Override public void flush() { flushed = true; }
        }
        TrackedReader reader = new TrackedReader(document());
        CallGraphSnapshot graph = GraphJson.read(reader);
        assertFalse(reader.closed);
        TrackedWriter writer = new TrackedWriter();
        GraphJson.write(graph, writer);
        assertFalse(writer.closed);
        assertFalse(writer.flushed);
        assertEquals(graph.edges(), GraphJson.fromJson(writer.toString()).edges());

        IOException failure = new IOException("caller I/O failed");
        Reader failingReader = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException { throw failure; }
            @Override public void close() {}
        };
        Writer failingWriter = new Writer() {
            @Override public void write(char[] buffer, int offset, int length) throws IOException { throw failure; }
            @Override public void flush() {}
            @Override public void close() {}
        };
        assertSame(failure, assertThrows(IOException.class, () -> GraphJson.read(failingReader)));
        assertSame(failure, assertThrows(IOException.class, () -> GraphJson.write(graph, failingWriter)));
    }
}
