package com.jordansamhi.androspecter.examples;

import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.graph.GraphDot;
import com.jordansamhi.androspecter.graph.GraphJson;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Queries an exported graph and optionally writes a reachable subgraph without opening Soot. */
public final class GraphQueries {
    private GraphQueries() {}

    /**
     * Imports versioned graph JSON, prints directed reachability and a shortest method path,
     * and optionally exports the source-reachable subgraph as DOT. Known starting nodes are
     * included in closures; a shortest path's edge count is its node count minus one.
     * The optional output file is replaced if it exists, and its parent must already exist.
     *
     * @param args input JSON path, source method signature, target method signature, and
     *             optionally an output DOT path
     * @throws IllegalArgumentException if the argument count or graph document is invalid
     * @throws IOException if an input or output file cannot be accessed
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: GraphQueries <graph.json> <source-signature> <target-signature> [reachable.dot]");
        }
        CallGraphSnapshot graph;
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            graph = GraphJson.read(reader);
        }
        Set<String> reachable = graph.reachableFrom(args[1]);
        System.out.println("Can reach: " + graph.canReach(args[1], args[2]));
        System.out.println("Shortest method path: " + graph.shortestPath(args[1], args[2]));
        System.out.println("Forward closure including source: " + reachable.size());
        System.out.println("Reverse closure including target: " + graph.reachableTo(args[2]).size());
        CallGraphSnapshot subgraph = graph.subgraph(reachable::contains);
        System.out.printf("Source-reachable subgraph: %d methods, %d edges%n",
                subgraph.methods().size(), subgraph.edges().size());
        if (args.length == 4) {
            try (Writer writer = Files.newBufferedWriter(Path.of(args[3]), StandardCharsets.UTF_8)) {
                GraphDot.write(subgraph, writer);
            }
            System.out.println("DOT: " + Path.of(args[3]).toAbsolutePath().normalize());
        }
    }
}
