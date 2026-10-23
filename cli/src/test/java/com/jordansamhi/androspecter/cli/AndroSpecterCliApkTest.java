package com.jordansamhi.androspecter.cli;

import com.google.gson.JsonObject;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.graph.GraphJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.jordansamhi.androspecter.cli.AndroSpecterCliTest.copyApk;
import static com.jordansamhi.androspecter.cli.AndroSpecterCliTest.run;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real APK/FlowDroid CLI checks, enabled with the same SDK property as library tests. */
@EnabledIfSystemProperty(named = "androspecter.androidPlatforms", matches = ".+")
@Timeout(120)
class AndroSpecterCliApkTest {
    private static final String SOURCE = "<demo.taint.MainActivity: java.lang.String source()>";
    private static final String SINK = "<demo.taint.MainActivity: void sink(java.lang.String)>";
    private static final String ON_CREATE = "<demo.taint.MainActivity: void onCreate(android.os.Bundle)>";
    @TempDir Path directory;

    @Test
    void inspectionAndGraphCommandsAnalyzeTheBundledApkAndReleaseTheirSession() throws Exception {
        Path apk = copyApk(directory);
        String platforms = System.getProperty("androspecter.androidPlatforms");
        var inspection = run("inspect", "--apk", apk.toString(), "--platforms", platforms,
                "--package", "demo.taint", "--list-methods", "--invocations");
        assertEquals(0, inspection.status(), inspection.error());
        JsonObject report = inspection.json();
        assertEquals("demo.taint", report.get("packageName").getAsString());
        assertTrue(report.get("classCount").getAsInt() >= 1);
        assertTrue(report.get("methodCount").getAsInt() >= 3);
        assertTrue(report.get("invocationCount").getAsLong() >= 3);
        JsonObject activity = report.getAsJsonArray("classes").asList().stream()
                .map(value -> value.getAsJsonObject())
                .filter(value -> value.get("name").getAsString().equals("demo.taint.MainActivity"))
                .findFirst().orElseThrow();
        assertTrue(activity.getAsJsonArray("methods").asList().stream()
                .anyMatch(value -> value.getAsString().equals(SOURCE)));

        Path output = directory.resolve("graph.json");
        var exported = run("graph", "--apk", apk.toString(), "--platforms", platforms,
                "--package", "demo.taint", "-o", output.toString());
        assertEquals(0, exported.status(), exported.error());
        assertEquals("", exported.output());
        CallGraphSnapshot graph = GraphJson.fromJson(Files.readString(output));
        assertTrue(graph.calleesOf(ON_CREATE).contains(SOURCE));
        assertTrue(graph.calleesOf(ON_CREATE).contains(SINK));

        var query = run("query", "--input-json", output.toString(), "--source", ON_CREATE, "--target", SINK);
        assertEquals(0, query.status(), query.error());
        assertTrue(query.json().get("canReach").getAsBoolean());
        assertEquals(List.of(ON_CREATE, SINK), query.json().getAsJsonArray("shortestPath").asList().stream()
                .map(value -> value.getAsString()).toList());
    }

    @Test
    void realTaintFindingUsesOptInExitFourAndPathNoneSurvivesJsonSerialization() throws Exception {
        Path apk = copyApk(directory);
        Path definitions = Files.writeString(directory.resolve("sources-sinks.txt"),
                "SOURCE|" + SOURCE + "\nSINK|" + SINK + "\n");
        List<String> arguments = new ArrayList<>(List.of("taint", "--apk", apk.toString(),
                "--platforms", System.getProperty("androspecter.androidPlatforms"),
                "--sources-sinks", definitions.toString(), "--paths", "none"));
        arguments.add("--fail-on-findings");
        var failOnFindings = run(arguments.toArray(String[]::new));
        assertEquals(4, failOnFindings.status(), failOnFindings.error());
        assertEquals("SUCCESS", failOnFindings.json().get("status").getAsString());
        var findings = failOnFindings.json().getAsJsonArray("findings");
        assertEquals(1, findings.size());
        JsonObject finding = findings.get(0).getAsJsonObject();
        assertFalse(finding.get("pathAvailable").getAsBoolean());
        assertTrue(finding.getAsJsonArray("path").isEmpty());

        arguments.remove("--fail-on-findings");
        var ordinary = run(arguments.toArray(String[]::new));
        assertEquals(0, ordinary.status(), ordinary.error());
        assertEquals("SUCCESS", ordinary.json().get("status").getAsString());
        assertEquals(1, ordinary.json().getAsJsonArray("findings").size());
    }
}
