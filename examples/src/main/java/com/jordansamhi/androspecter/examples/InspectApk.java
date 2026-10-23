package com.jordansamhi.androspecter.examples;

import com.jordansamhi.androspecter.ResultsAccumulator;
import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.inspection.Inspection;

import java.nio.file.Path;

/**
 * Counts invocations in explicitly selected application classes and queries a detached graph.
 * Each invocation metric counts a static call site, not a runtime execution or graph edge.
 */
public final class InspectApk {
    private InspectApk() {}

    /**
     * Inspects an APK on the calling thread, then prints metrics and queries callers/callees
     * after the analysis closes. Without an explicit method signature, the query selects
     * the first graph method within the supplied package prefix, if one exists.
     *
     * @param args APK path, SDK platforms directory, package prefix, and optionally a full
     *             Jimple method signature, such as {@code <example.App: void run()>}
     * @throws IllegalArgumentException if the argument count or package prefix is invalid
     * @throws RuntimeException if APK initialization or inspection fails
     */
    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: InspectApk <apk> <platforms> <package-prefix> [method-signature]");
        }
        String prefix = args[2].strip();
        if (prefix.isEmpty() || prefix.equals(".")) {
            throw new IllegalArgumentException("Provide a nonempty application package prefix");
        }
        String classPrefix = prefix.endsWith(".") ? prefix : prefix + ".";

        CallGraphSnapshot graph;
        ResultsAccumulator.Snapshot metrics;
        try (AndroidAnalysis analysis = AndroidAnalysis.open(Path.of(args[0]), Path.of(args[1]))) {
            ResultsAccumulator counters = analysis.metrics();
            counters.setAppName(analysis.packageName());
            counters.setMetric("classes", 0);
            counters.setMetric("methods", 0);
            counters.setMetric("invocations", 0);
            analysis.inspect(Inspection.builder()
                    // Filter before visiting methods or loading their bodies.
                    .classes(clazz -> clazz.isApplicationClass()
                            && clazz.getName().startsWith(classPrefix))
                    .onClass(clazz -> counters.incrementMetric("classes"))
                    .onMethod(method -> counters.incrementMetric("methods"))
                    .onInvoke((caller, statement, invocation) -> counters.incrementMetric("invocations"))
                    .build());
            metrics = counters.snapshot();
            graph = analysis.callGraph();
        }

        // These values contain no live Soot objects and remain usable after close/reset.
        System.out.println("Package: " + metrics.appName());
        System.out.println("Selected application metrics: " + metrics.metrics());
        System.out.printf("Full graph: %d incident methods, %d edges%n",
                graph.methods().size(), graph.edges().size());
        String query = args.length == 4 ? args[3] : graph.methods().stream()
                .filter(signature -> signature.startsWith("<" + classPrefix))
                .findFirst().orElse(null);
        if (query == null) {
            System.out.println("No graph method matches the package prefix; provide a method signature to query.");
        } else {
            System.out.println("Query: " + query);
            System.out.println("Present in graph: " + graph.containsMethod(query));
            System.out.println("Callers: " + graph.callersOf(query));
            System.out.println("Callees: " + graph.calleesOf(query));
        }
    }
}
