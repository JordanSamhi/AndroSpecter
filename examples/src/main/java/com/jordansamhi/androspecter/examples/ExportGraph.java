package com.jordansamhi.androspecter.examples;

import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.graph.GraphJson;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exports a detached graph in AndroSpecter's versioned JSON format. */
public final class ExportGraph {
    private ExportGraph() {}

    /**
     * Builds the complete call graph, closes the Soot session, and writes its snapshot.
     * The destination is replaced if present; its parent directory must already exist.
     * GraphQueries can later query the resulting file without reopening the APK.
     *
     * @param args APK path, Android SDK platforms directory, and output JSON path
     * @throws IllegalArgumentException if three arguments are not supplied
     * @throws IOException if the graph file cannot be written
     * @throws RuntimeException if APK initialization or graph capture fails
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: ExportGraph <apk> <platforms> <output-json>");
        }
        CallGraphSnapshot graph;
        try (AndroidAnalysis analysis = AndroidAnalysis.open(Path.of(args[0]), Path.of(args[1]))) {
            graph = analysis.callGraph();
        }
        try (Writer writer = Files.newBufferedWriter(Path.of(args[2]), StandardCharsets.UTF_8)) {
            GraphJson.write(graph, writer);
        }
        System.out.printf("Exported %d methods and %d edges to %s%n",
                graph.methods().size(), graph.edges().size(), args[2]);
    }
}
