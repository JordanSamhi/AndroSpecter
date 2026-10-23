package com.jordansamhi.androspecter.cli;

import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
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
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "query", description = "Query reachability and a shortest call path in saved graph JSON without Soot.")
final class QueryCommand extends CliSupport implements Callable<Integer> {
    @Option(names = "--input-json", required = true, paramLabel = "FILE", description = "Existing GraphJson document.") Path input;
    @Option(names = "--source", required = true, paramLabel = "SIGNATURE", description = "Source method's full Jimple signature.") String source;
    @Option(names = "--target", required = true, paramLabel = "SIGNATURE", description = "Target method's full Jimple signature.") String target;
    @Option(names = "--format", defaultValue = "JSON", description = "Output format: ${COMPLETION-CANDIDATES}.") ReportFormat format;
    @Mixin OutputOptions output;

    /**
     * Queries detached graph indexes, including explicit unknown-node flags.
     * @return success status
     * @throws Exception if I/O fails
     */
    @Override public Integer call() throws Exception {
        requireFile(input, "Graph JSON input");
        output.validate(this, List.of(input));
        CallGraphSnapshot graph;
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            graph = GraphJson.read(reader);
        }
        List<String> path = graph.shortestPath(source, target);
        QueryResult result = new QueryResult(1, graph.analysisId(), source, target,
                graph.containsMethod(source), graph.containsMethod(target), !path.isEmpty(),
                path, graph.callersOf(source), graph.calleesOf(source));
        write(output, List.of(input), writer -> {
            if (format == ReportFormat.JSON) JSON.toJson(result, writer);
            else writer.write("Source known: " + result.sourceKnown() + "\nTarget known: " + result.targetKnown()
                    + "\nCan reach: " + result.canReach() + "\nShortest method path: " + result.shortestPath()
                    + "\nSource callers: " + result.sourceCallers() + "\nSource callees: " + result.sourceCallees()
                    + "\nStatic graph relationships do not prove feasible runtime execution or taint flow.\n");
        });
        return 0;
    }

    record QueryResult(int schemaVersion, UUID analysisId, String source, String target,
                       boolean sourceKnown, boolean targetKnown, boolean canReach,
                       List<String> shortestPath, Set<String> sourceCallers, Set<String> sourceCallees) { }
}
