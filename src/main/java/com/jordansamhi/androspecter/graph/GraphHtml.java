package com.jordansamhi.androspecter.graph;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exports detached graphs as standalone, interactive HTML files that work offline.
 * All methods, isolated nodes, parallel edges, Soot edge kinds, and call-site metadata
 * are embedded in a compact, indexed viewer payload. The browser builds linear-size
 * indexes and displays a bounded, directed one-hop neighborhood: at most 60 methods
 * and 180 relationships grouped by source, target, and kind. Search covers every
 * method; visible limits and omitted counts are reported in the page. Exporting does
 * not require an active analysis session or modify the snapshot.
 *
 * <p>The generated document contains its scripts and styles, uses no external
 * dependencies or network requests, and treats graph strings as text. It still holds
 * the entire graph in browser memory; rendering limits are not an import-size limit.
 * For a smaller file, export a {@link CallGraphSnapshot#subgraph(java.util.function.Predicate)
 * filtered snapshot} first.</p>
 * <p>The embedded indexed payload is an internal viewer format, not a {@link GraphJson}
 * interchange document. Export JSON separately when another program must import the graph.</p>
 */
public final class GraphHtml {
    private static final String RESOURCE_ROOT = "/com/jordansamhi/androspecter/graph/";

    private GraphHtml() {}

    /**
     * Creates a complete UTF-8-compatible HTML document, including all graph data.
     * The returned string can be saved as an {@code .html} file and opened directly
     * in a modern browser. Prefer {@link #write(CallGraphSnapshot, Writer)} for large
     * graphs to avoid retaining another complete document string in Java memory.
     *
     * @param graph non-null immutable snapshot to explore
     * @return self-contained HTML with searchable methods and an interactive graph
     * @throws NullPointerException if graph is null
     * @throws UncheckedIOException if a bundled viewer resource cannot be read
     * @throws IllegalStateException if a bundled viewer resource is missing
     */
    public static String toHtml(CallGraphSnapshot graph) {
        StringWriter destination = new StringWriter();
        try {
            write(graph, destination);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot create graph viewer", failure);
        }
        return destination.toString();
    }

    /**
     * Writes an offline graph viewer without closing or flushing the caller's writer.
     * Use a UTF-8 writer when saving the document. Graph strings are escaped before
     * embedding in the non-executable JSON block, including characters that could
     * otherwise terminate a script element; dynamic labels are rendered as text.
     * Output includes all data even when the browser truncates the visible graph.
     * An I/O failure may leave partial output, so callers requiring atomic files
     * should publish a temporary file only after this method succeeds.
     * <pre>{@code
     * try (Writer writer = Files.newBufferedWriter(Path.of("graph.html"), StandardCharsets.UTF_8)) {
     *     GraphHtml.write(snapshot, writer);
     * }
     * }</pre>
     *
     * @param graph non-null detached snapshot
     * @param destination non-null caller-owned character writer
     * @throws NullPointerException if either argument is null
     * @throws IOException if reading viewer resources or writing the document fails
     * @throws IllegalStateException if a bundled viewer resource is missing
     */
    public static void write(CallGraphSnapshot graph, Writer destination) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(destination, "destination");
        copyResource("viewer-start.html", destination);
        copyResource("viewer.css", destination);
        copyResource("viewer-body.html", destination);
        writeData(graph, destination);
        destination.write("\n</script>\n<script>\n");
        copyResource("viewer.js", destination);
        destination.write("\n</script>\n</body>\n</html>\n");
    }

    private static void copyResource(String name, Writer destination) throws IOException {
        InputStream stream = GraphHtml.class.getResourceAsStream(RESOURCE_ROOT + name);
        if (stream == null) {
            throw new IllegalStateException("Missing graph viewer resource: " + name);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            reader.transferTo(destination);
        }
    }

    /** Keeps repeated signatures and kinds in tables while retaining every edge. */
    private static void writeData(CallGraphSnapshot graph, Writer destination) throws IOException {
        JsonWriter json = new JsonWriter(destination);
        json.setStrictness(Strictness.STRICT);
        json.setHtmlSafe(true);
        json.setSerializeNulls(true);
        Map<String, Integer> methodIds = new HashMap<>();
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (CallGraphEdge edge : graph.edges()) {
            kinds.computeIfAbsent(edge.kind(), ignored -> kinds.size());
        }
        json.beginObject();
        json.name("viewerVersion").value(1);
        json.name("analysisId").value(graph.analysisId().toString());
        json.name("methods").beginArray();
        for (String method : graph.methods()) {
            methodIds.put(method, methodIds.size());
            json.value(method);
        }
        json.endArray();
        json.name("kinds").beginArray();
        for (String kind : kinds.keySet()) json.value(kind);
        json.endArray();
        json.name("edges").beginArray();
        for (CallGraphEdge edge : graph.edges()) {
            json.beginArray();
            json.value(methodIds.get(edge.source()));
            json.value(methodIds.get(edge.target()));
            json.value(kinds.get(edge.kind()));
            json.value(edge.callSite());
            json.value(edge.callSiteIndex());
            json.endArray();
        }
        json.endArray();
        json.endObject();
    }
}
