package com.jordansamhi.androspecter.graph;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;

/**
 * Graphviz DOT export for detached graphs. Nodes include explicitly retained isolated
 * methods, and every parallel edge is emitted separately. Use {@link GraphJson} for
 * versioned, lossless interchange; DOT is a visualization format and has no importer here.
 * NUL characters are rejected because Graphviz cannot represent them in quoted DOT strings.
 * Export only writes text: rendering that text requires a separate Graphviz installation.
 * No Soot state is accessed and the snapshot is not modified.
 */
public final class GraphDot {
    private GraphDot() {}

    /**
     * Renders a graph as DOT using deterministic snapshot order. Method signatures are
     * quoted identifiers; edge kinds are labels and available statements are tooltips.
     * Backslashes, quotes, and line breaks are escaped. No external process is started.
     *
     * @param graph non-null detached graph
     * @return DOT text suitable for a Graphviz input file
     * @throws NullPointerException if graph is null
     * @throws IllegalArgumentException if a method identifier, edge kind, or statement contains NUL
     */
    public static String toDot(CallGraphSnapshot graph) {
        StringWriter writer = new StringWriter();
        try {
            write(graph, writer);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return writer.toString();
    }

    /**
     * Writes a non-strict directed DOT graph, preserving parallel edge statements. Each
     * edge carries its kind as a label, its capture-time index as {@code callSiteIndex},
     * and an optional statement tooltip. The analysis ID is not represented. This method
     * neither closes nor flushes the caller's writer; an I/O failure may leave partial output.
     * NUL characters are rejected before any output is written; use JSON for such values.
     *
     * @param graph non-null detached graph
     * @param destination non-null caller-owned character writer
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if a method identifier, edge kind, or statement contains NUL
     * @throws IOException if writing fails
     */
    public static void write(CallGraphSnapshot graph, Writer destination) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(destination, "destination");
        for (String method : graph.methods()) {
            rejectNul(method);
        }
        for (CallGraphEdge edge : graph.edges()) {
            rejectNul(edge.kind());
            if (edge.callSite() != null) {
                rejectNul(edge.callSite());
            }
        }
        destination.write("digraph CallGraph {\n");
        for (String method : graph.methods()) {
            destination.write("  " + quote(method) + ";\n");
        }
        for (CallGraphEdge edge : graph.edges()) {
            destination.write("  " + quote(edge.source()) + " -> " + quote(edge.target())
                    + " [label=" + quote(edge.kind()) + ", callSiteIndex=" + quote(Integer.toString(edge.callSiteIndex())));
            if (edge.callSite() != null) {
                destination.write(", tooltip=" + quote(edge.callSite()));
            }
            destination.write("];\n");
        }
        destination.write("}\n");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static void rejectNul(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("DOT identifiers and attributes must not contain NUL characters");
        }
    }
}
