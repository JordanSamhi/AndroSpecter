package com.jordansamhi.androspecter.cli;

import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.analysis.InitializationMode;
import com.jordansamhi.androspecter.inspection.Inspection;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(name = "inspect", description = "Inspect application classes and methods without building a call graph.")
final class InspectCommand extends CliSupport implements Callable<Integer> {
    @Option(names = "--apk", required = true, paramLabel = "FILE", description = "APK to inspect.") Path apk;
    @Option(names = "--list-methods", description = "Include sorted method signatures for each selected class.") boolean listMethods;
    @Option(names = "--invocations", description = "Load method bodies and count static invocation sites.") boolean invocations;
    @Option(names = "--format", defaultValue = "JSON", description = "Output format: ${COMPLETION-CANDIDATES}.") ReportFormat format;
    @Mixin AnalysisOptions options;
    @Mixin PackageOptions scope;
    @Mixin OutputOptions output;

    /**
     * Inspects selected application classes and emits detached counts.
     * @return success status
     * @throws Exception if analysis or I/O fails
     */
    @Override public Integer call() throws Exception {
        for (String option : List.of("--algorithm", "--callback-timeout")) {
            if (spec.commandLine().getParseResult().hasMatchedOption(option)) {
                throw usage(option + " is unavailable for class-only inspection; use graph or taint");
            }
        }
        options.validate(this, apk);
        scope.validate(this);
        output.validate(this, options.inputs(apk));
        TreeMap<String, Counts> counts = new TreeMap<>();
        InspectionReport report;
        try (var control = new AnalysisOptions.RunControl(options.timeout);
             AndroidAnalysis analysis = options.builder(apk, control)
                     .initializationMode(InitializationMode.CLASSES_ONLY).open()) {
            var inspection = Inspection.builder()
                    .classes(clazz -> clazz.isApplicationClass() && scope.matchesClass(clazz.getName()))
                    .onClass(clazz -> counts.put(clazz.getName(), new Counts()))
                    .onMethod(method -> {
                        Counts count = counts.get(method.getDeclaringClass().getName());
                        count.methods++;
                        if (listMethods) count.signatures.add(method.getSignature());
                    });
            if (invocations) inspection.onInvoke((caller, statement, expression) ->
                    counts.get(caller.getDeclaringClass().getName()).invocations++);
            analysis.inspect(inspection.build());
            List<ClassReport> classes = counts.entrySet().stream().map(entry ->
                    new ClassReport(entry.getKey(), entry.getValue().methods,
                            invocations ? entry.getValue().invocations : null,
                            listMethods ? entry.getValue().signatures.stream().sorted().toList() : null)).toList();
            report = new InspectionReport(1, analysis.id(), analysis.packageName(), classes.size(),
                    classes.stream().mapToInt(ClassReport::methodCount).sum(),
                    invocations ? classes.stream().mapToLong(ClassReport::invocationCount).sum() : null, classes);
        }
        write(output, options.inputs(apk), writer -> {
            if (format == ReportFormat.JSON) JSON.toJson(report, writer);
            else {
                writer.write("Package: " + report.packageName() + "\nSelected application classes: " + report.classCount()
                        + "\nMethods: " + report.methodCount() + "\nStatic invocation sites: "
                        + (report.invocationCount() == null ? "not requested" : report.invocationCount()) + "\n");
                for (ClassReport clazz : report.classes()) {
                    writer.write(clazz.name() + " (" + clazz.methodCount() + " methods)\n");
                    if (clazz.methods() != null) for (String method : clazz.methods()) writer.write("  " + method + "\n");
                }
            }
        });
        return 0;
    }

    private static final class Counts {
        int methods;
        long invocations;
        final List<String> signatures = new ArrayList<>();
    }
    record ClassReport(String name, int methodCount, Long invocationCount, List<String> methods) { }
    record InspectionReport(int schemaVersion, UUID analysisId, String packageName, int classCount,
                            int methodCount, Long invocationCount, List<ClassReport> classes) { }
}
