package com.jordansamhi.androspecter.examples;

import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.instrumentation.InstrumentationOptions;
import com.jordansamhi.androspecter.instrumentation.Logger;
import com.jordansamhi.androspecter.instrumentation.TransformationPipeline;

import java.nio.file.Path;

/** Inserts scoped method-coverage probes in memory and captures the rebuilt call graph. */
public final class InstrumentInMemory {
    private InstrumentInMemory() {}

    /**
     * Captures the original graph, applies one logger through a transformation pipeline,
     * and captures a rebuilt graph. Only application classes under the supplied package
     * prefix are instrumented. No APK is exported, installed, signed, or executed.
     *
     * @param args APK path, SDK platforms directory, and application package prefix
     * @throws IllegalArgumentException if the argument count or package prefix is invalid
     * @throws RuntimeException if initialization, instrumentation, or graph rebuilding fails
     */
    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: InstrumentInMemory <apk> <platforms> <package-prefix>");
        }
        String prefix = args[2].strip();
        if (prefix.isEmpty() || prefix.equals(".")) {
            throw new IllegalArgumentException("Provide a nonempty application package prefix");
        }
        String classPrefix = prefix.endsWith(".") ? prefix : prefix + ".";

        CallGraphSnapshot before;
        CallGraphSnapshot after;
        try (AndroidAnalysis analysis = AndroidAnalysis.open(Path.of(args[0]), Path.of(args[1]))) {
            before = analysis.callGraph();
            InstrumentationOptions options = InstrumentationOptions.builder()
                    .classes(clazz -> clazz.isApplicationClass()
                            && clazz.getName().startsWith(classPrefix))
                    .methods(method -> method.isConcrete())
                    .build();
            Logger logger = new Logger(analysis.context(), options);
            logger.logAllMethods("AndroSpecterExample", false);
            TransformationPipeline pipeline = TransformationPipeline.builder().add(logger).build();
            analysis.apply(pipeline);
            // apply() invalidates the cache; this call rebuilds before taking a new snapshot.
            after = analysis.callGraph();
        }

        System.out.printf("Before: %d incident methods, %d edges%n",
                before.methods().size(), before.edges().size());
        System.out.printf("After: %d incident methods, %d edges%n",
                after.methods().size(), after.edges().size());
        System.out.println("Only the in-memory scene was changed; no APK was written.");
    }
}
