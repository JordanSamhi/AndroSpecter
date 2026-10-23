package com.jordansamhi.androspecter.graph;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;

import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned JSON interchange for detached call graphs, independent of Soot's lifecycle.
 * Version 1 has required {@code schemaVersion}, {@code analysisId}, {@code methods}, and
 * {@code edges} fields. Every edge requires {@code source}, {@code target}, {@code kind},
 * {@code callSite}, and {@code callSiteIndex}; only callSite may be null. Unknown extra
 * fields are ignored. Imports preserve isolated nodes and parallel edges and rebuild indexes.
 */
public final class GraphJson {
    /** The schema version emitted and accepted by this implementation. */
    public static final int SCHEMA_VERSION = 1;

    private GraphJson() {}

    /**
     * Serializes a snapshot, including its analysis identity and all edge metadata. Output
     * uses deterministic snapshot order and represents unavailable statements explicitly as null.
     *
     * @param graph non-null detached graph
     * @return pretty-printed JSON in the supported schema version
     * @throws NullPointerException if graph is null
     */
    public static String toJson(CallGraphSnapshot graph) {
        StringWriter writer = new StringWriter();
        try {
            write(graph, writer);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return writer.toString();
    }

    /**
     * Writes a complete graph document without closing or flushing the caller's writer.
     * Output contains an explicit node array so isolated nodes survive a round trip. An
     * I/O failure can leave partial output; callers needing atomic files should write to
     * a temporary file and publish it after successful completion.
     *
     * @param graph non-null detached graph
     * @param destination non-null caller-owned character writer
     * @throws NullPointerException if either argument is null
     * @throws IOException if writing fails
     */
    public static void write(CallGraphSnapshot graph, Writer destination) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(destination, "destination");
        JsonWriter json = new JsonWriter(destination);
        json.setStrictness(Strictness.STRICT);
        json.setIndent("  ");
        json.setSerializeNulls(true);
        json.beginObject();
        json.name("schemaVersion").value(SCHEMA_VERSION);
        json.name("analysisId").value(graph.analysisId().toString());
        json.name("methods").beginArray();
        for (String method : graph.methods()) {
            json.value(method);
        }
        json.endArray();
        json.name("edges").beginArray();
        for (CallGraphEdge edge : graph.edges()) {
            json.beginObject();
            json.name("source").value(edge.source());
            json.name("target").value(edge.target());
            json.name("kind").value(edge.kind());
            json.name("callSite").value(edge.callSite());
            json.name("callSiteIndex").value(edge.callSiteIndex());
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }

    /**
     * Parses and validates a graph without accessing Soot. Field order is unrestricted;
     * unknown extra fields are ignored. Required fields, their types, the UUID, version,
     * edge positions, and endpoint membership are validated. Input must contain one strict
     * JSON document: comments, unquoted names, and trailing documents are rejected.
     *
     * @param json non-null complete JSON document
     * @return immutable graph with rebuilt indexes and the original analysis identity
     * @throws NullPointerException if json is null
     * @throws IllegalArgumentException if the document is malformed, incomplete, inconsistent,
     *         has duplicate required fields, or uses an unsupported schema version
     */
    public static CallGraphSnapshot fromJson(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return read(new StringReader(json));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /**
     * Reads one complete graph document and validates it as described by {@link #fromJson(String)}.
     * Reads to the end of the supplied stream to reject trailing documents. This method
     * does not close the caller's reader. Duplicate method entries are normalized to one
     * node; equal edge occurrences remain separate and are never deduplicated.
     *
     * @param source non-null caller-owned character reader
     * @return immutable graph retaining isolated nodes, parallel edges, and analysis identity
     * @throws NullPointerException if source is null
     * @throws IllegalArgumentException if JSON or graph data is invalid or the version is unsupported
     * @throws IOException if reading fails for a reason other than malformed JSON
     */
    public static CallGraphSnapshot read(Reader source) throws IOException {
        Objects.requireNonNull(source, "source");
        JsonReader json = new JsonReader(source);
        json.setStrictness(Strictness.STRICT);
        try {
            expect(json, JsonToken.BEGIN_OBJECT, "graph object");
            json.beginObject();
            Set<String> fields = new HashSet<>();
            Integer version = null;
            UUID analysisId = null;
            List<String> methods = null;
            List<CallGraphEdge> edges = null;
            while (json.hasNext()) {
                String name = json.nextName();
                switch (name) {
                    case "schemaVersion" -> {
                        unique(fields, name, json);
                        version = integer(json, name);
                    }
                    case "analysisId" -> {
                        unique(fields, name, json);
                        String value = string(json, name);
                        analysisId = UUID.fromString(value);
                        if (!analysisId.toString().equalsIgnoreCase(value)) {
                            throw invalid(json, "analysisId must be a canonical UUID");
                        }
                    }
                    case "methods" -> {
                        unique(fields, name, json);
                        methods = methods(json);
                    }
                    case "edges" -> {
                        unique(fields, name, json);
                        edges = edges(json);
                    }
                    default -> skipValidatedValue(json);
                }
            }
            json.endObject();
            if (!fields.containsAll(Set.of("schemaVersion", "analysisId", "methods", "edges"))) {
                throw invalid(json, "Missing required graph field");
            }
            if (version != SCHEMA_VERSION) {
                throw invalid(json, "Unsupported graph schema version: " + version);
            }
            if (json.peek() != JsonToken.END_DOCUMENT) {
                throw invalid(json, "Trailing content after graph document");
            }
            return CallGraphSnapshot.of(analysisId, methods, edges);
        } catch (MalformedJsonException | EOFException | IllegalStateException | NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid graph JSON at " + json.getPath() + ": "
                    + failure.getMessage(), failure);
        }
    }

    private static List<String> methods(JsonReader json) throws IOException {
        expect(json, JsonToken.BEGIN_ARRAY, "methods array");
        json.beginArray();
        List<String> methods = new ArrayList<>();
        while (json.hasNext()) {
            methods.add(string(json, "method"));
        }
        json.endArray();
        return methods;
    }

    private static List<CallGraphEdge> edges(JsonReader json) throws IOException {
        expect(json, JsonToken.BEGIN_ARRAY, "edges array");
        json.beginArray();
        List<CallGraphEdge> edges = new ArrayList<>();
        while (json.hasNext()) {
            expect(json, JsonToken.BEGIN_OBJECT, "edge object");
            json.beginObject();
            Set<String> fields = new HashSet<>();
            String source = null;
            String target = null;
            String kind = null;
            String callSite = null;
            int callSiteIndex = -1;
            while (json.hasNext()) {
                String name = json.nextName();
                switch (name) {
                    case "source" -> {
                        unique(fields, name, json);
                        source = string(json, name);
                    }
                    case "target" -> {
                        unique(fields, name, json);
                        target = string(json, name);
                    }
                    case "kind" -> {
                        unique(fields, name, json);
                        kind = string(json, name);
                    }
                    case "callSite" -> {
                        unique(fields, name, json);
                        if (json.peek() == JsonToken.NULL) {
                            json.nextNull();
                        } else {
                            callSite = string(json, name);
                        }
                    }
                    case "callSiteIndex" -> {
                        unique(fields, name, json);
                        callSiteIndex = integer(json, name);
                    }
                    default -> skipValidatedValue(json);
                }
            }
            json.endObject();
            if (!fields.containsAll(Set.of("source", "target", "kind", "callSite", "callSiteIndex"))) {
                throw invalid(json, "Missing required edge field");
            }
            edges.add(new CallGraphEdge(source, target, callSite, kind, callSiteIndex));
        }
        json.endArray();
        return edges;
    }

    private static void skipValidatedValue(JsonReader json) throws IOException {
        // Gson's optimized skipValue does not apply all strict string checks.
        // Consume tokens normally so ignored extensions obey the same JSON grammar.
        int depth = 0;
        do {
            switch (json.peek()) {
                case BEGIN_OBJECT -> { json.beginObject(); depth++; }
                case BEGIN_ARRAY -> { json.beginArray(); depth++; }
                case END_OBJECT -> { json.endObject(); depth--; }
                case END_ARRAY -> { json.endArray(); depth--; }
                case NAME -> json.nextName();
                case STRING, NUMBER -> json.nextString();
                case BOOLEAN -> json.nextBoolean();
                case NULL -> json.nextNull();
                default -> throw invalid(json, "Expected an extension value");
            }
        } while (depth > 0);
    }

    private static String string(JsonReader json, String field) throws IOException {
        expect(json, JsonToken.STRING, field + " string");
        return json.nextString();
    }

    private static int integer(JsonReader json, String field) throws IOException {
        expect(json, JsonToken.NUMBER, field + " integer");
        try {
            return new BigDecimal(json.nextString()).intValueExact();
        } catch (ArithmeticException failure) {
            throw invalid(json, field + " must be an exact 32-bit integer");
        }
    }

    private static void expect(JsonReader json, JsonToken expected, String description) throws IOException {
        if (json.peek() != expected) {
            throw invalid(json, "Expected " + description);
        }
    }

    private static void unique(Set<String> fields, String name, JsonReader json) {
        if (!fields.add(name)) {
            throw invalid(json, "Duplicate field: " + name);
        }
    }

    private static IllegalArgumentException invalid(JsonReader json, String message) {
        return new IllegalArgumentException(message + " at " + json.getPath());
    }
}
