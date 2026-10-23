package com.jordansamhi.androspecter.cli;

import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.analysis.InitializationMode;
import com.jordansamhi.androspecter.analysis.PathReconstructionMode;
import com.jordansamhi.androspecter.files.SourceSinkConfiguration;
import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import com.jordansamhi.androspecter.result.TaintResultFormatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "taint", description = "Run source-to-sink analysis using an explicit SOURCE|/SINK| definition file.")
final class TaintCommand extends CliSupport implements Callable<Integer> {
    @Option(names = "--apk", required = true, paramLabel = "FILE", description = "APK to analyze.") Path apk;
    @Option(names = "--sources-sinks", required = true, paramLabel = "FILE", description = "UTF-8 SOURCE|<signature> and SINK|<signature> definitions.") Path definitions;
    @Option(names = "--paths", defaultValue = "PRECISE", description = "Path reconstruction: ${COMPLETION-CANDIDATES}.") PathReconstructionMode paths;
    @Option(names = "--taint-timeout", defaultValue = "0", paramLabel = "SECONDS", description = "Data-flow phase timeout; 0 disables it.") int taintTimeout;
    @Option(names = "--path-timeout", defaultValue = "0", paramLabel = "SECONDS", description = "Path-reconstruction phase timeout; 0 disables it.") int pathTimeout;
    @Option(names = "--fail-on-findings", description = "Exit 4 if a successful analysis reports findings (default: exit 0).") boolean failOnFindings;
    @Option(names = "--format", defaultValue = "JSON", description = "Output format: ${COMPLETION-CANDIDATES}.") ReportFormat format;
    @Mixin AnalysisOptions options;
    @Mixin OutputOptions output;

    /**
     * Runs taint analysis and exports findings even when incomplete.
     * @return status documented by the CLI
     * @throws Exception if setup or I/O fails
     */
    @Override public Integer call() throws Exception {
        options.validate(this, apk);
        requireFile(definitions, "Source/sink definitions");
        if (taintTimeout < 0 || pathTimeout < 0) throw usage("Timeouts must be nonnegative seconds");
        SourceSinkConfiguration settings;
        try {
            settings = SourceSinkConfiguration.fromFile(definitions);
        } catch (IllegalArgumentException failure) {
            throw usage(failure.getMessage());
        }
        if (settings.sources().isEmpty() || settings.sinks().isEmpty()) {
            throw usage("The definition file must contain at least one SOURCE and one SINK");
        }
        List<Path> inputs = options.inputs(apk);
        inputs.add(definitions);
        output.validate(this, inputs);
        TaintAnalysisResult result;
        try (var control = new AnalysisOptions.RunControl(options.timeout);
             AndroidAnalysis analysis = options.builder(apk, control)
                     .initializationMode(InitializationMode.CLASSES_ONLY)
                     .pathReconstruction(paths).sourcesAndSinks(settings)
                     .taintTimeout(Duration.ofSeconds(taintTimeout))
                     .pathTimeout(Duration.ofSeconds(pathTimeout)).open()) {
            result = analysis.analyzeTaint();
        }
        write(output, inputs, writer -> {
            if (format == ReportFormat.JSON) JSON.toJson(result, writer);
            else {
                writer.write("Status: " + result.status() + "\nFindings: " + result.findings().size() + "\n");
                for (String diagnostic : result.diagnostics()) writer.write("Diagnostic: " + diagnostic + "\n");
                for (var finding : result.findings()) writer.write(TaintResultFormatter.formatFinding(finding) + "\n");
            }
        });
        err().println("Taint status: " + result.status() + "; findings: " + result.findings().size());
        return exitStatus(result, failOnFindings);
    }

    static int exitStatus(TaintAnalysisResult result, boolean failOnFindings) {
        if (result.status() == AnalysisStatus.SUCCESS) return failOnFindings && !result.findings().isEmpty() ? 4 : 0;
        return result.status() == AnalysisStatus.FAILED ? 1 : 3;
    }
}
