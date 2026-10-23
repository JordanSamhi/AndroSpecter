package com.jordansamhi.androspecter.examples;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.files.SourceSinkConfiguration;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import com.jordansamhi.androspecter.result.TaintFinding;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads explicit source/sink definitions and exports a detached taint result as JSON. */
public final class AnalyzeTaint {
    private AnalyzeTaint() {}

    /**
     * Runs taint analysis synchronously, closes the analysis, and then reports and serializes
     * its result. Output includes status, diagnostics, endpoints, and available paths. The
     * output file is replaced if it already exists; its parent directory must already exist.
     *
     * @param args APK path, SDK platforms directory, UTF-8 source/sink definition file,
     *             and output JSON path
     * @throws IllegalArgumentException if the argument count or definitions are invalid
     * @throws IOException if the JSON file cannot be written
     * @throws RuntimeException if definitions cannot be read or APK initialization fails
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: AnalyzeTaint <apk> <platforms> <sources-sinks-file> <output-json>");
        }
        SourceSinkConfiguration configuration = SourceSinkConfiguration.fromFile(Path.of(args[2]));
        System.out.printf("Loaded %d sources and %d sinks%n",
                configuration.sources().size(), configuration.sinks().size());

        TaintAnalysisResult result;
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(Path.of(args[0]), Path.of(args[1]))
                .sourcesAndSinks(configuration)
                .open()) {
            result = analysis.analyzeTaint();
        }

        // Reporting and JSON serialization deliberately happen after the Soot session closes.
        System.out.println("Status: " + result.status());
        System.out.println("Findings: " + result.findings().size());
        for (String diagnostic : result.diagnostics()) {
            System.out.println("Diagnostic: " + diagnostic);
        }
        for (TaintFinding finding : result.findings()) {
            System.out.println("Source: " + finding.source().description());
            System.out.println("Sink: " + finding.sink().description());
            System.out.printf("Path available: %s; steps: %d%n",
                    finding.pathAvailable(), finding.path().size());
        }

        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            gson.toJson(result, writer);
        }
        System.out.println("JSON: " + output);
    }
}
