package com.jordansamhi.androspecter.cli;

import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.graph.GraphDot;
import com.jordansamhi.androspecter.graph.GraphHtml;
import com.jordansamhi.androspecter.graph.GraphJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "graph", description = "Build or reload a static call graph, then export JSON, DOT, or interactive HTML.")
final class GraphCommand extends CliSupport implements Callable<Integer> {
    enum Format { JSON, DOT, HTML }
    @Option(names = "--apk", paramLabel = "FILE", description = "APK to analyze (requires --platforms; excludes --input-json).") Path apk;
    @Option(names = "--input-json", paramLabel = "FILE", description = "Existing GraphJson document; offline mode, no SDK needed.") Path input;
    @Option(names = "--format", defaultValue = "JSON", description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).") Format format;
    @Option(names = "--from", paramLabel = "SIGNATURE", description = "Export only methods reachable from this method, after package filtering.") String from;
    @Mixin AnalysisOptions options;
    @Mixin PackageOptions scope;
    @Mixin OutputOptions output;

    /**
     * Builds or converts one graph and writes it after closing Soot.
     * @return success status
     * @throws Exception if analysis or I/O fails
     */
    @Override public Integer call() throws Exception {
        if ((apk == null) == (input == null)) throw usage("Choose exactly one of --apk or --input-json");
        scope.validate(this);
        List<Path> inputs;
        if (apk != null) {
            options.validate(this, apk);
            inputs = options.inputs(apk);
        } else {
            options.rejectForOffline(this);
            requireFile(input, "Graph JSON input");
            inputs = List.of(input);
        }
        output.validate(this, inputs);
        CallGraphSnapshot graph;
        if (input != null) {
            try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
                graph = GraphJson.read(reader);
            }
        } else {
            err().println("Building static call graph (" + options.algorithm + ")...");
            try (var control = new AnalysisOptions.RunControl(options.timeout);
                 AndroidAnalysis analysis = options.builder(apk, control).open()) {
                graph = analysis.callGraph();
            }
        }
        int originalMethods = graph.methods().size();
        int originalEdges = graph.edges().size();
        if (!scope.prefixes.isEmpty()) graph = graph.subgraph(scope::matchesMethod);
        if (from != null) {
            if (!graph.containsMethod(from)) throw usage("--from method is absent from the selected graph: " + from);
            Set<String> reachable = graph.reachableFrom(from);
            graph = graph.subgraph(reachable::contains);
        }
        CallGraphSnapshot selected = graph;
        write(output, inputs, writer -> {
            switch (format) {
                case JSON -> GraphJson.write(selected, writer);
                case DOT -> GraphDot.write(selected, writer);
                case HTML -> GraphHtml.write(selected, writer);
            }
        });
        err().printf("Exported %d methods and %d edge occurrences (input: %d methods, %d edges).%n",
                selected.methods().size(), selected.edges().size(), originalMethods, originalEdges);
        return 0;
    }
}
