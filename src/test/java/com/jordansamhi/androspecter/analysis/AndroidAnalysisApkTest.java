package com.jordansamhi.androspecter.analysis;

import com.jordansamhi.androspecter.files.SourceSinkConfiguration;
import com.jordansamhi.androspecter.graph.CallGraphSnapshot;
import com.jordansamhi.androspecter.inspection.Inspection;
import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import soot.Scene;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.Jimple;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real FlowDroid/Android regression checks; the regular unit suite needs no SDK. */
@EnabledIfSystemProperty(named = "androspecter.androidPlatforms", matches = ".+")
@Timeout(120)
class AndroidAnalysisApkTest {
    private static final String SOURCE = "<demo.taint.MainActivity: java.lang.String source()>";
    private static final String SINK = "<demo.taint.MainActivity: void sink(java.lang.String)>";
    private static final String ON_CREATE = "<demo.taint.MainActivity: void onCreate(android.os.Bundle)>";
    @TempDir Path directory;

    private Path apk() throws Exception {
        Path apk = directory.resolve("fixture.apk");
        try (InputStream input = getClass().getResourceAsStream("/apks/managed-analysis.apk")) {
            assertNotNull(input, "The reproducible APK fixture must be on the test classpath");
            Files.copy(input, apk);
        }
        return apk;
    }

    private Path platforms() {
        return Path.of(System.getProperty("androspecter.androidPlatforms"));
    }

    @Test
    void classInspectionDefersGraphAndPromotionPreservesChangedBodies() throws Exception {
        CallGraphSnapshot graph;
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(apk(), platforms())
                .initializationMode(InitializationMode.CLASSES_ONLY).open()) {
            assertFalse(Scene.v().hasCallGraph(), "Class-only initialization must not construct the graph");
            AtomicInteger calls = new AtomicInteger();
            analysis.inspect(Inspection.builder()
                    .classes(clazz -> clazz.getName().equals("demo.taint.MainActivity"))
                    .onInvoke((caller, statement, expression) -> calls.incrementAndGet()).build());
            assertTrue(calls.get() >= 3, "Inspection must load real DEX method bodies before graph construction");
            assertFalse(Scene.v().hasCallGraph());

            SootMethod source = Scene.v().getMethod(SOURCE);
            Unit inserted = Jimple.v().newNopStmt();
            analysis.apply(context -> source.retrieveActiveBody().getUnits()
                    .insertBefore(inserted, source.getActiveBody().getUnits().getLast()));
            graph = analysis.callGraph();
            assertTrue(source.getActiveBody().getUnits().contains(inserted),
                    "Lazy callback/graph construction must preserve transformations in the loaded scene");
            assertTrue(graph.calleesOf(ON_CREATE).contains(SOURCE));
            assertTrue(graph.calleesOf(ON_CREATE).contains(SINK));
            assertSame(graph, analysis.callGraph(), "Repeated graph queries reuse the snapshot");
        }
        assertTrue(graph.canReach(ON_CREATE, SINK));
        assertEquals(java.util.List.of(ON_CREATE, SINK), graph.shortestPath(ON_CREATE, SINK));
    }

    @Test
    void taintPromotesClassOnlySessionAndHonorsDisabledPathReconstruction() throws Exception {
        TaintAnalysisResult result;
        SourceSinkConfiguration definitions = SourceSinkConfiguration.builder()
                .source(SOURCE).sink(SINK).build();
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(apk(), platforms())
                .initializationMode(InitializationMode.CLASSES_ONLY)
                .pathReconstruction(PathReconstructionMode.NONE)
                .sourcesAndSinks(definitions).open()) {
            assertFalse(Scene.v().hasCallGraph());
            result = analysis.analyzeTaint();
            assertEquals(AnalysisStatus.SUCCESS, result.status(), result.diagnostics().toString());
            assertEquals(1, result.findings().size());
            assertTrue(result.findings().stream().allMatch(finding ->
                    !finding.pathAvailable() && finding.path().isEmpty()));
            assertDeclaredLocals();
            assertTrue(analysis.callGraph().calleesOf(ON_CREATE).contains(SINK));
            TaintAnalysisResult repeated = analysis.analyzeTaint();
            assertEquals(AnalysisStatus.SUCCESS, repeated.status(), repeated.diagnostics().toString());
            assertEquals(1, repeated.findings().size());
            assertDeclaredLocals();
        }
        assertEquals(1, result.findings().size(), "Findings remain detached after closure");
    }

    private void assertDeclaredLocals() {
        for (soot.SootClass clazz : Scene.v().getClasses()) {
            for (SootMethod method : clazz.getMethods()) {
                if (method.hasActiveBody()) {
                    method.getActiveBody().validateLocals();
                }
            }
        }
    }
}
