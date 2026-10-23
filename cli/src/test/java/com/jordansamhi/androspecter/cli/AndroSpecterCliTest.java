package com.jordansamhi.androspecter.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jordansamhi.androspecter.graph.CallGraphEdge;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.graph.GraphJson;
import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import soot.G;
import soot.Scene;
import soot.SootClass;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real CLI commands without an Android SDK or a running Soot analysis. */
class AndroSpecterCliTest {
    private static final UUID ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String A = "<demo.app.Entry: void start()>";
    private static final String B = "<demo.app.Middle: void next()>";
    private static final String C = "<demo.app.End: void finish()>";
    private static final String ISOLATED = "<demo.app.Isolated: void idle()>";
    private static final String OUTSIDE = "<demo.application.Other: void outside()>";
    private static final String BRIDGE = "<external.Bridge: void bridge()>";
    private static final String UNKNOWN = "<missing.Unknown: void absent()>";
    @TempDir Path directory;

    @BeforeEach
    void isolateSoot() {
        G.reset();
    }

    @AfterEach
    void releaseSoot() {
        G.reset();
    }

    @Test
    void helpVersionAndMissingCommandHaveDocumentedStatuses() {
        G generation = G.v();
        Invocation help = run("--help");
        assertEquals(0, help.status, help.error);
        for (String command : List.of("metadata", "inspect", "graph", "query", "taint")) {
            assertTrue(help.output.contains(command), command);
            Invocation commandHelp = run(command, "--help");
            assertEquals(0, commandHelp.status, commandHelp.error);
            assertTrue(commandHelp.output.contains("Usage:"));
        }
        Invocation version = run("--version");
        assertEquals(0, version.status, version.error);
        assertTrue(version.output.strip().startsWith("AndroSpecter "));
        Invocation missing = run();
        assertEquals(2, missing.status);
        assertTrue(missing.output.contains("Usage:"));
        assertSame(generation, G.v());
    }

    @Test
    void usageErrorsDoNotStartAnalysisOrProduceResults() throws Exception {
        Path input = graphFile("input.json", graph());
        Path apk = copyApk(directory);
        Path platforms = Files.createDirectory(directory.resolve("platforms"));
        Path invalidClasspath = Files.writeString(directory.resolve("library" + File.pathSeparatorChar + "part.jar"), "");
        Path sourceOnly = Files.writeString(directory.resolve("source-only.txt"), "SOURCE|" + A + "\n");
        Path malformedDefinitions = Files.writeString(directory.resolve("invalid-definitions.txt"), "UNKNOWN|" + A + "\n");
        G generation = G.v();
        List<String[]> invalid = List.of(
                new String[]{"unknown-command"},
                new String[]{"metadata"},
                new String[]{"metadata", "--apk", apk.toString(), "--format", "xml"},
                new String[]{"graph"},
                new String[]{"graph", "--input-json", input.toString(), "--apk", apk.toString()},
                new String[]{"graph", "--input-json", input.toString(), "--algorithm", "CHA"},
                new String[]{"graph", "--input-json", input.toString(), "--package", "demo..app"},
                new String[]{"query", "--input-json", input.toString(), "--source", A},
                new String[]{"inspect", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--timeout", "-1"},
                new String[]{"inspect", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--algorithm", "CHA"},
                new String[]{"inspect", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--callback-timeout", "0"},
                new String[]{"graph", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--callback-timeout", "-1"},
                new String[]{"inspect", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--classpath", invalidClasspath.toString()},
                new String[]{"taint", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--sources-sinks", directory.resolve("missing-definitions.txt").toString()},
                new String[]{"taint", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--sources-sinks", sourceOnly.toString()},
                new String[]{"taint", "--apk", apk.toString(), "--platforms", platforms.toString(),
                        "--sources-sinks", malformedDefinitions.toString()});
        for (String[] arguments : invalid) {
            Invocation result = run(arguments);
            assertEquals(2, result.status, String.join(" ", arguments) + "\n" + result.error);
            assertEquals("", result.output);
            assertFalse(result.error.isBlank());
            assertSame(generation, G.v());
        }
    }

    @Test
    void metadataReadsTheBundledApkWithoutReplacingTheScene() throws Exception {
        Path apk = copyApk(directory);
        G generation = G.v();
        Scene scene = Scene.v();
        SootClass marker = new SootClass("test.PreservedScene");
        scene.addClass(marker);

        Invocation result = run("metadata", "--apk", apk.toString());

        assertEquals(0, result.status, result.error);
        JsonObject json = result.json();
        assertEquals("demo.taint", json.get("packageName").getAsString());
        assertEquals("1.0", json.get("versionName").getAsString());
        assertEquals(1, json.get("versionCode").getAsLong());
        assertEquals(21, json.get("minSdkVersion").getAsInt());
        assertEquals(30, json.get("targetSdkVersion").getAsInt());
        JsonObject activity = json.getAsJsonArray("components").get(0).getAsJsonObject();
        assertEquals("ACTIVITY", activity.get("kind").getAsString());
        assertEquals("demo.taint.MainActivity", activity.get("name").getAsString());
        assertTrue(activity.get("declaredExported").getAsBoolean());
        assertSame(generation, G.v());
        assertSame(scene, Scene.v());
        assertSame(marker, Scene.v().getSootClass(marker.getName()));
        assertEquals("", result.error);
    }

    @Test
    void malformedApkIsAnExecutionFailureWithoutJsonOutput() throws Exception {
        Path apk = Files.writeString(directory.resolve("invalid.apk"), "not a ZIP archive");
        Invocation result = run("metadata", "--apk", apk.toString());
        assertEquals(1, result.status);
        assertEquals("", result.output);
        assertTrue(result.error.contains("Error:"));
    }

    @Test
    void offlineJsonRoundTripsEmptyIsolatedAndParallelGraphs() throws Exception {
        G generation = G.v();
        int index = 0;
        for (CallGraphSnapshot expected : graphShapes()) {
            Path input = graphFile("shape-" + index++ + ".json", expected);
            Invocation result = run("graph", "--input-json", input.toString());
            assertEquals(0, result.status, result.error);
            assertGraphEquals(expected, GraphJson.fromJson(result.output));
            assertSame(generation, G.v());
        }
    }

    @Test
    void offlineDotAndHtmlKeepIsolatedNodesAndParallelOccurrences() throws Exception {
        G generation = G.v();
        int index = 0;
        for (CallGraphSnapshot expected : graphShapes()) {
            Path input = graphFile("render-" + index++ + ".json", expected);
            Invocation dot = run("graph", "--input-json", input.toString(), "--format", "dot");
            assertEquals(0, dot.status, dot.error);
            assertTrue(dot.output.startsWith("digraph CallGraph {"));
            assertEquals(expected.edges().size(), dot.output.lines().filter(line -> line.contains(" -> ")).count());
            if (expected.containsMethod(ISOLATED)) assertTrue(dot.output.contains("\"" + ISOLATED + "\";"));

            Invocation html = run("graph", "--input-json", input.toString(), "--format", "html");
            assertEquals(0, html.status, html.error);
            assertTrue(html.output.toLowerCase(java.util.Locale.ROOT).contains("<!doctype html>"));
            var data = Pattern.compile("<script id=\"graph-data\" type=\"application/json\">(.*?)</script>",
                    Pattern.DOTALL).matcher(html.output);
            assertTrue(data.find(), "The standalone HTML must include its complete graph data");
            assertGraphEquals(expected, decodeHtmlGraph(data.group(1)));
            assertFalse(html.output.contains("<script src="));
            assertSame(generation, G.v());
        }
    }

    @Test
    void packageSelectionUsesBoundariesBeforeTakingReachableClosure() throws Exception {
        CallGraphSnapshot original = CallGraphSnapshot.of(ID, List.of(A, B, C, ISOLATED, OUTSIDE, BRIDGE),
                List.of(edge(A, BRIDGE), edge(BRIDGE, B), edge(B, C), edge(A, OUTSIDE)));
        Path input = graphFile("scope.json", original);
        Invocation selected = run("graph", "--input-json", input.toString(), "--package", "demo.app");
        assertEquals(0, selected.status, selected.error);
        CallGraphSnapshot scoped = GraphJson.fromJson(selected.output);
        assertEquals(Set.of(A, B, C, ISOLATED), scoped.methods());
        assertEquals(List.of(edge(B, C)), scoped.edges());

        Invocation closure = run("graph", "--input-json", input.toString(), "--package", "demo.app", "--from", A);
        assertEquals(0, closure.status, closure.error);
        assertEquals(Set.of(A), GraphJson.fromJson(closure.output).methods());

        Invocation combined = run("graph", "--input-json", input.toString(),
                "--package", "demo.app", "--package", "external", "--from", A);
        assertEquals(0, combined.status, combined.error);
        assertEquals(Set.of(A, BRIDGE, B, C), GraphJson.fromJson(combined.output).methods());

        Invocation missing = run("graph", "--input-json", input.toString(), "--package", "demo.app", "--from", OUTSIDE);
        assertEquals(2, missing.status);
        assertEquals("", missing.output);
    }

    @Test
    void queriesDistinguishUnknownUnreachableAndIdentityPaths() throws Exception {
        Path input = graphFile("query.json", graph());
        G generation = G.v();
        record QueryCase(String source, String target, boolean sourceKnown, boolean targetKnown,
                         boolean reachable, List<String> path) { }
        for (QueryCase query : List.of(
                new QueryCase(A, C, true, true, true, List.of(A, B, C)),
                new QueryCase(C, A, true, true, false, List.of()),
                new QueryCase(ISOLATED, ISOLATED, true, true, true, List.of(ISOLATED)),
                new QueryCase(A, ISOLATED, true, true, false, List.of()),
                new QueryCase(UNKNOWN, A, false, true, false, List.of()),
                new QueryCase(A, UNKNOWN, true, false, false, List.of()),
                new QueryCase(UNKNOWN, UNKNOWN, false, false, false, List.of()))) {
            Invocation result = run("query", "--input-json", input.toString(),
                    "--source", query.source, "--target", query.target);
            assertEquals(0, result.status, result.error);
            JsonObject json = result.json();
            assertEquals(ID.toString(), json.get("analysisId").getAsString());
            assertEquals(query.sourceKnown, json.get("sourceKnown").getAsBoolean());
            assertEquals(query.targetKnown, json.get("targetKnown").getAsBoolean());
            assertEquals(query.reachable, json.get("canReach").getAsBoolean());
            assertEquals(query.path, json.getAsJsonArray("shortestPath").asList().stream()
                    .map(value -> value.getAsString()).toList());
            assertSame(generation, G.v());
        }
    }

    @Test
    void malformedGraphFailsBeforeReplacingAnExistingOutput() throws Exception {
        Path input = Files.writeString(directory.resolve("invalid.json"), "{\"schemaVersion\":1}");
        Path output = Files.writeString(directory.resolve("result.dot"), "old result");
        Invocation result = run("graph", "--input-json", input.toString(), "--format", "dot",
                "--output", output.toString(), "--overwrite");
        assertEquals(1, result.status);
        assertEquals("", result.output);
        assertEquals("old result", Files.readString(output));
        assertTrue(result.error.contains("Error:"));
    }

    @Test
    void existingOutputIsProtectedByDefault() throws Exception {
        Path input = graphFile("input.json", graph());
        Path output = Files.writeString(directory.resolve("output.json"), "keep this");
        Invocation result = run("graph", "--input-json", input.toString(), "-o", output.toString());
        assertEquals(2, result.status, result.error);
        assertEquals("keep this", Files.readString(output));
        assertEquals("", result.output);
        assertTrue(result.error.contains("--overwrite"));
    }

    @Test
    void explicitOverwritePublishesACompleteResultWithoutStdoutData() throws Exception {
        Path input = graphFile("input.json", graph());
        Path output = Files.writeString(directory.resolve("output.json"), "old data");
        Invocation result = run("graph", "--input-json", input.toString(), "-o", output.toString(), "--overwrite");
        assertEquals(0, result.status, result.error);
        assertGraphEquals(graph(), GraphJson.fromJson(Files.readString(output)));
        assertEquals("", result.output);
        assertTrue(result.error.contains("Written:"));
    }

    @Test
    void overwriteCannotReplaceAnInputViaItsPathSymlinkOrHardLink() throws Exception {
        Path input = graphFile("input.json", graph());
        byte[] original = Files.readAllBytes(input);
        Path symbolic = Files.createSymbolicLink(directory.resolve("symbolic.json"), input);
        Path hard = Files.createLink(directory.resolve("hard.json"), input);
        for (Path output : List.of(input, symbolic, hard)) {
            Invocation result = run("graph", "--input-json", input.toString(), "-o", output.toString(), "--overwrite");
            assertEquals(2, result.status, result.error);
            assertTrue(result.error.contains("must not replace an input"));
            assertArrayEquals(original, Files.readAllBytes(input));
            assertArrayEquals(original, Files.readAllBytes(output));
            assertEquals("", result.output);
        }
        assertTrue(Files.isSymbolicLink(symbolic));
        assertTrue(Files.isSameFile(input, hard));
    }

    @Test
    void failedSerializationPreservesTheOldTargetAndRemovesTemporaryFiles() throws Exception {
        Path output = Files.writeString(directory.resolve("output.json"), "previous valid result");
        WriteHarness harness = new WriteHarness();
        StringWriter diagnostics = new StringWriter();
        new CommandLine(harness).setErr(new PrintWriter(diagnostics));
        CliSupport.OutputOptions options = new CliSupport.OutputOptions();
        options.path = output;
        options.overwrite = true;
        IOException expected = new IOException("serialization interrupted");

        IOException actual = assertThrows(IOException.class, () -> harness.write(options, List.of(), writer -> {
            writer.write("{\"partial\":");
            throw expected;
        }));

        assertSame(expected, actual);
        assertEquals("previous valid result", Files.readString(output));
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(output), entries.toList());
        }
        assertEquals("", diagnostics.toString());
    }

    @Test
    void repeatedRunsDoNotInheritScopeFormatOutputOrOverwriteOptions() throws Exception {
        Path input = graphFile("input.json", graph());
        Path output = Files.writeString(directory.resolve("output.dot"), "old");
        Invocation first = run("graph", "--input-json", input.toString(), "--package", "demo.app",
                "--from", ISOLATED, "--format", "dot", "-o", output.toString(), "--overwrite");
        assertEquals(0, first.status, first.error);
        String firstFile = Files.readString(output);
        assertTrue(firstFile.contains(ISOLATED));
        assertFalse(firstFile.contains(A));

        Invocation second = run("graph", "--input-json", input.toString());
        assertEquals(0, second.status, second.error);
        assertGraphEquals(graph(), GraphJson.fromJson(second.output));
        assertEquals(firstFile, Files.readString(output));
        Invocation third = run("graph", "--input-json", input.toString(), "-o", output.toString());
        assertEquals(2, third.status, "--overwrite must not leak from an earlier invocation");
        assertEquals(firstFile, Files.readString(output));
    }

    @Test
    void embeddingRetainsCallerWritersAndGlobalConsoleSettings() {
        TrackingWriter output = new TrackingWriter();
        TrackingWriter error = new TrackingWriter();
        var standardOutput = System.out;
        var standardError = System.err;
        String logLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");
        int status = AndroSpecterCli.run(new String[]{"--help"}, new PrintWriter(output), new PrintWriter(error));
        assertEquals(0, status);
        assertFalse(output.closed);
        assertFalse(error.closed);
        assertSame(standardOutput, System.out);
        assertSame(standardError, System.err);
        assertEquals(logLevel, System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
        assertTrue(output.toString().contains("Usage:"));
    }

    @Test
    void incompleteTaintStatusesCannotBeReportedAsSuccessOrFindingsOnly() {
        for (boolean failOnFindings : List.of(false, true)) {
            assertEquals(3, TaintCommand.exitStatus(new TaintAnalysisResult(ID, AnalysisStatus.CANCELLED,
                    List.of(), List.of("cancelled")), failOnFindings));
            assertEquals(3, TaintCommand.exitStatus(new TaintAnalysisResult(ID, AnalysisStatus.TIMED_OUT,
                    List.of(), List.of("phase limit")), failOnFindings));
            assertEquals(1, TaintCommand.exitStatus(new TaintAnalysisResult(ID, AnalysisStatus.FAILED,
                    List.of(), List.of("backend failure")), failOnFindings));
            assertEquals(0, TaintCommand.exitStatus(new TaintAnalysisResult(ID, AnalysisStatus.SUCCESS,
                    List.of(), List.of()), failOnFindings));
        }
    }

    static Path copyApk(Path destinationDirectory) throws IOException {
        Path base = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path fixture = base.resolve("../src/test/resources/apks/managed-analysis.apk").normalize();
        if (!Files.isRegularFile(fixture)) fixture = base.resolve("src/test/resources/apks/managed-analysis.apk");
        assertTrue(Files.isRegularFile(fixture), "The repository's reproducible APK fixture must exist: " + fixture);
        return Files.copy(fixture, destinationDirectory.resolve("fixture.apk"));
    }

    static Invocation run(String... arguments) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int status = AndroSpecterCli.run(arguments, new PrintWriter(output), new PrintWriter(error));
        return new Invocation(status, output.toString(), error.toString());
    }

    private Path graphFile(String name, CallGraphSnapshot graph) throws IOException {
        return Files.writeString(directory.resolve(name), GraphJson.toJson(graph), StandardCharsets.UTF_8);
    }

    private static CallGraphEdge edge(String source, String target) {
        return new CallGraphEdge(source, target, null, "STATIC", -1);
    }

    private static CallGraphSnapshot graph() {
        CallGraphEdge parallel = new CallGraphEdge(A, B, "staticinvoke " + B + "; // </script>", "STATIC", 4);
        return CallGraphSnapshot.of(ID, List.of(A, B, C, ISOLATED, OUTSIDE),
                List.of(parallel, parallel, edge(B, C),
                        new CallGraphEdge(A, OUTSIDE, "virtualinvoke outside()", "VIRTUAL", 6), edge(OUTSIDE, C)));
    }

    private static List<CallGraphSnapshot> graphShapes() {
        return List.of(CallGraphSnapshot.of(ID, List.of(), List.of()),
                CallGraphSnapshot.of(ID, List.of(ISOLATED), List.of()), graph());
    }

    private static void assertGraphEquals(CallGraphSnapshot expected, CallGraphSnapshot actual) {
        assertEquals(expected.analysisId(), actual.analysisId());
        assertEquals(expected.methods(), actual.methods());
        assertEquals(expected.edges(), actual.edges());
    }

    private static CallGraphSnapshot decodeHtmlGraph(String payload) {
        JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
        assertEquals(1, json.get("viewerVersion").getAsInt());
        List<String> methods = json.getAsJsonArray("methods").asList().stream()
                .map(value -> value.getAsString()).toList();
        List<String> kinds = json.getAsJsonArray("kinds").asList().stream()
                .map(value -> value.getAsString()).toList();
        List<CallGraphEdge> edges = new ArrayList<>();
        for (var value : json.getAsJsonArray("edges")) {
            var tuple = value.getAsJsonArray();
            assertEquals(5, tuple.size());
            edges.add(new CallGraphEdge(methods.get(tuple.get(0).getAsInt()),
                    methods.get(tuple.get(1).getAsInt()),
                    tuple.get(3).isJsonNull() ? null : tuple.get(3).getAsString(),
                    kinds.get(tuple.get(2).getAsInt()), tuple.get(4).getAsInt()));
        }
        return CallGraphSnapshot.of(UUID.fromString(json.get("analysisId").getAsString()), methods, edges);
    }

    record Invocation(int status, String output, String error) {
        JsonObject json() { return JsonParser.parseString(output).getAsJsonObject(); }
    }

    @CommandLine.Command(name = "write-test")
    private static final class WriteHarness extends CliSupport { }

    private static final class TrackingWriter extends StringWriter {
        private boolean closed;
        @Override public void close() { closed = true; }
    }
}
