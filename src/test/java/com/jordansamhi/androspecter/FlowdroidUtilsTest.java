package com.jordansamhi.androspecter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soot.G;
import soot.jimple.Jimple;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.results.InfoflowResults;
import soot.jimple.infoflow.results.ResultSinkInfo;
import soot.jimple.infoflow.results.ResultSourceInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlowdroidUtilsTest {
    @TempDir
    Path platforms;

    @AfterEach
    void resetSoot() {
        G.reset();
    }

    @Test
    void configuresFileInputsAndReusesConstructedCallGraph() {
        StubUtils utils = new StubUtils();
        SetupApplication app = utils.initializeFlowdroid(platforms.toString(), null, "spark");
        assertEquals(platforms.toFile(), app.getConfig().getAnalysisFileConfig().getAndroidPlatformDir());
        assertEquals(new File("example.apk"), app.getConfig().getAnalysisFileConfig().getTargetAPKFile());
        assertEquals(InfoflowConfiguration.CallgraphAlgorithm.SPARK, app.getConfig().getCallgraphAlgorithm());
        assertEquals(1, utils.application.callGraphConstructions);
        assertEquals(InfoflowConfiguration.SootIntegrationMode.UseExistingInstance,
                app.getConfig().getSootIntegrationMode());
    }

    @Test
    void failedReinitializationDoesNotReusePreviousAnalysis() {
        StubUtils utils = initialized();
        utils.failCallGraph = true;
        assertThrows(IllegalStateException.class,
                () -> utils.initializeFlowdroid(platforms.toString(), null, "CHA"));
        assertThrows(IllegalStateException.class, () -> analyze(utils));
    }

    @Test
    void rejectsUnknownCallGraphAlgorithm() {
        assertThrows(IllegalArgumentException.class,
                () -> new StubUtils().initializeFlowdroid(platforms.toString(), null, "SPRAK"));
    }

    @Test
    void requiresInitialization() {
        assertThrows(IllegalStateException.class, () -> analyze(new StubUtils()));
    }

    @Test
    void propagatesAnalysisFailureWithItsCause() {
        StubUtils utils = initialized();
        IOException failure = new IOException("analysis failed");
        utils.application.failure = failure;
        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> analyze(utils));
        assertSame(failure, actual.getCause());
    }

    @Test
    void distinguishesMissingResultFromSuccessfulEmptyAnalysis() {
        StubUtils utils = initialized();
        assertTrue(analyze(utils).isEmpty());
        utils.application.results = null;
        assertThrows(IllegalStateException.class, () -> analyze(utils));
    }

    @Test
    void rejectsErrorsReportedInsideResults() {
        StubUtils utils = initialized();
        utils.application.results.addException("solver failed");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> analyze(utils));
        assertTrue(failure.getMessage().contains("solver failed"));
    }

    @Test
    void rejectsIncompleteResults() {
        StubUtils utils = initialized();
        utils.application.results.setTerminationState(InfoflowResults.TERMINATION_DATA_FLOW_TIMEOUT);
        assertThrows(IllegalStateException.class, () -> analyze(utils));
    }

    @Test
    void retainsDetectedFlowWhenReconstructedPathIsMissing() {
        StubUtils utils = initialized();
        ResultSinkInfo sink = new ResultSinkInfo(null, AccessPath.getEmptyAccessPath(),
                Jimple.v().newNopStmt());
        ResultSourceInfo source = new ResultSourceInfo(null, AccessPath.getEmptyAccessPath(),
                Jimple.v().newNopStmt(), true);
        assertNull(source.getPath());
        utils.application.results.addResult(sink, source);
        Set<String> reports = analyze(utils);
        assertEquals(1, reports.size());
        String report = reports.iterator().next();
        assertTrue(report.contains("Found leak:"));
        assertTrue(report.contains("Detailed path unavailable"));
        assertTrue(report.contains("  - To "));
    }

    private StubUtils initialized() {
        StubUtils utils = new StubUtils();
        utils.initializeFlowdroid(platforms.toString(), null, "CHA");
        return utils;
    }

    private Set<String> analyze(FlowdroidUtils utils) {
        return utils.runTaintAnalysis(Collections.emptySet(), Collections.emptySet());
    }

    private static final class StubUtils extends FlowdroidUtils {
        StubApplication application;
        boolean failCallGraph;

        StubUtils() {
            super("example.apk");
        }

        @Override
        SetupApplication createSetupApplication(InfoflowAndroidConfiguration configuration) {
            application = new StubApplication(configuration, failCallGraph);
            return application;
        }
    }

    private static final class StubApplication extends SetupApplication {
        InfoflowResults results = new InfoflowResults();
        IOException failure;
        final boolean failCallGraph;
        int callGraphConstructions;

        StubApplication(InfoflowAndroidConfiguration configuration, boolean failCallGraph) {
            super(configuration);
            this.failCallGraph = failCallGraph;
        }

        @Override
        public void constructCallgraph() {
            if (failCallGraph) {
                throw new IllegalStateException("call graph construction failed");
            }
            callGraphConstructions++;
        }

        @Override
        public InfoflowResults runInfoflow(Set<AndroidMethod> sources, Set<AndroidMethod> sinks)
                throws IOException {
            if (failure != null) {
                throw failure;
            }
            return results;
        }
    }
}
