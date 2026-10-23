package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import com.jordansamhi.androspecter.result.TaintFinding;
import com.jordansamhi.androspecter.result.TaintResultFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soot.G;
import soot.Local;
import soot.Modifier;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.VoidType;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.Stmt;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    @Test
    void customizerRunsBeforeApplicationCreationAndCallGraphConstruction() {
        StubUtils utils = new StubUtils();
        SetupApplication app = utils.initializeFlowdroid(platforms.toString(), null, "CHA", config -> {
            assertNull(utils.application);
            config.setDataFlowTimeout(123);
        });
        assertEquals(123, app.getConfig().getDataFlowTimeout());
        assertEquals(123, utils.application.timeoutAtConstruction);
    }

    @Test
    void structuredFindingsRemainUsableAfterSootReset() {
        StubUtils utils = initialized();
        String methodSignature = addFindingWithPath(utils.application.results, false);
        UUID id = UUID.randomUUID();
        TaintAnalysisResult result = utils.runTaintAnalysisResult(Collections.emptySet(), Collections.emptySet(), id);
        assertEquals(id, result.analysisId());
        assertEquals(AnalysisStatus.SUCCESS, result.status());
        assertEquals(1, result.findings().size());
        TaintFinding finding = result.findings().get(0);
        assertEquals(methodSignature, finding.source().methodSignature());
        assertEquals(methodSignature, finding.sink().methodSignature());
        assertEquals(2, finding.path().size());
        assertTrue(finding.pathAvailable());
        assertEquals(methodSignature, finding.path().get(0).methodSignature());

        G.reset();
        assertEquals(methodSignature, finding.path().get(1).methodSignature());
        assertEquals("nop", finding.source().statement());
        assertTrue(TaintResultFormatter.format(result).iterator().next().contains(methodSignature));
        assertThrows(UnsupportedOperationException.class, result.findings()::clear);
        assertThrows(UnsupportedOperationException.class, finding.path()::clear);
    }

    @Test
    void detachedEndpointsExposeInvokedSourceAndSinkApisWithoutParsingStatements() {
        StubUtils utils = initialized();
        SootClass owner = new SootClass("example.Api");
        Scene.v().addClass(owner);
        RefType stringType = RefType.v("java.lang.String");
        SootMethod sourceApi = new SootMethod("source", List.of(), stringType, Modifier.PUBLIC | Modifier.STATIC);
        SootMethod sinkApi = new SootMethod("sink", List.of(stringType), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        SootMethod caller = new SootMethod("onCreate", List.of(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(sourceApi);
        owner.addMethod(sinkApi);
        owner.addMethod(caller);
        JimpleBody body = Jimple.v().newBody(caller);
        caller.setActiveBody(body);
        Local value = Jimple.v().newLocal("value", stringType);
        body.getLocals().add(value);
        Stmt source = Jimple.v().newAssignStmt(value, Jimple.v().newStaticInvokeExpr(sourceApi.makeRef()));
        Stmt sink = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(sinkApi.makeRef(), value));
        body.getUnits().addAll(List.of(source, sink, Jimple.v().newReturnVoidStmt()));
        AccessPath accessPath = AccessPath.getEmptyAccessPath();
        utils.application.results.addResult(new ResultSinkInfo(null, accessPath, sink),
                new ResultSourceInfo(null, accessPath, source, true));

        String sourceSignature = sourceApi.getSignature();
        String sinkSignature = sinkApi.getSignature();
        String callerSignature = caller.getSignature();
        String accessPathText = accessPath.toString();
        TaintAnalysisResult result = analyzeStructured(utils);
        G.reset();
        List<TaintFinding> matching = result.findings().stream()
                .filter(finding -> sourceSignature.equals(finding.source().invokedMethodSignature()))
                .filter(finding -> sinkSignature.equals(finding.sink().invokedMethodSignature()))
                .toList();
        assertEquals(1, matching.size());
        assertEquals(callerSignature, matching.get(0).source().methodSignature());
        assertEquals(callerSignature, matching.get(0).sink().methodSignature());
        assertEquals(accessPathText, matching.get(0).source().accessPath());
        assertEquals(accessPathText, matching.get(0).sink().accessPath());
    }

    @Test
    void distinguishesAnEmptyReconstructedPathFromAnUnavailablePath() {
        StubUtils utils = initialized();
        addFindingWithPath(utils.application.results, true);
        TaintFinding finding = analyzeStructured(utils).findings().get(0);
        assertTrue(finding.pathAvailable());
        assertTrue(finding.path().isEmpty());

        utils.application.results = new InfoflowResults();
        utils.application.results.addResult(
                new ResultSinkInfo(null, AccessPath.getEmptyAccessPath(), Jimple.v().newNopStmt()),
                new ResultSourceInfo(null, AccessPath.getEmptyAccessPath(), Jimple.v().newNopStmt(), true));
        assertFalse(analyzeStructured(utils).findings().get(0).pathAvailable());
    }

    @Test
    void structuredFailuresAndTimeoutsRetainAvailableFindings() {
        StubUtils utils = initialized();
        addFindingWithPath(utils.application.results, false);
        utils.application.results.addException("solver failed");
        TaintAnalysisResult failure = analyzeStructured(utils);
        assertEquals(AnalysisStatus.FAILED, failure.status());
        assertEquals(1, failure.findings().size());
        assertTrue(failure.diagnostics().contains("solver failed"));

        utils.application.results.setTerminationState(InfoflowResults.TERMINATION_DATA_FLOW_TIMEOUT);
        TaintAnalysisResult timeout = analyzeStructured(utils);
        assertEquals(AnalysisStatus.TIMED_OUT, timeout.status());
        assertEquals(1, timeout.findings().size());
        assertThrows(IllegalStateException.class, () -> TaintResultFormatter.format(timeout));
    }

    @Test
    void structuredExecutionFailureIsNotAnEmptySuccess() {
        StubUtils utils = initialized();
        utils.application.failure = new IOException("cannot analyze");
        TaintAnalysisResult result = analyzeStructured(utils);
        assertEquals(AnalysisStatus.FAILED, result.status());
        assertTrue(result.findings().isEmpty());
        assertTrue(result.diagnostics().get(0).contains("cannot analyze"));
    }

    @Test
    void cancellationBeforeExecutionDoesNotRunTheEngine() {
        StubUtils utils = initialized();
        utils.abortAnalysis();
        assertEquals(1, utils.application.abortRequests);
        assertEquals(AnalysisStatus.CANCELLED, analyzeStructured(utils).status());
        assertEquals(0, utils.application.taintRuns);
        assertThrows(IllegalStateException.class, () -> analyze(utils));
    }

    @Test
    void cancellationDuringExecutionRetainsPartialFindings() {
        StubUtils utils = initialized();
        addFindingWithPath(utils.application.results, false);
        utils.application.duringRun = utils::abortAnalysis;
        TaintAnalysisResult result = analyzeStructured(utils);
        assertEquals(AnalysisStatus.CANCELLED, result.status());
        assertEquals(1, result.findings().size());
    }

    @Test
    void anInterruptedCallerReceivesCancelledWithoutLosingInterruptFlag() {
        StubUtils utils = initialized();
        Thread.currentThread().interrupt();
        try {
            assertEquals(AnalysisStatus.CANCELLED, analyzeStructured(utils).status());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, utils.application.taintRuns);
        } finally {
            Thread.interrupted();
        }
    }

    private TaintAnalysisResult analyzeStructured(FlowdroidUtils utils) {
        return utils.runTaintAnalysisResult(Collections.emptySet(), Collections.emptySet());
    }

    private String addFindingWithPath(InfoflowResults results, boolean emptyPath) {
        SootClass owner = new SootClass("example.ResultOwner");
        Scene.v().addClass(owner);
        SootMethod method = new SootMethod("flow", Collections.emptyList(), VoidType.v());
        owner.addMethod(method);
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        Stmt source = Jimple.v().newNopStmt();
        Stmt sink = Jimple.v().newNopStmt();
        body.getUnits().add(source);
        body.getUnits().add(sink);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        Stmt[] path = emptyPath ? new Stmt[0] : new Stmt[]{source, sink};
        AccessPath[] pathAccessPaths = new AccessPath[path.length];
        Arrays.fill(pathAccessPaths, AccessPath.getEmptyAccessPath());
        results.addResult(new ResultSinkInfo(null, AccessPath.getEmptyAccessPath(), sink),
                new ResultSourceInfo(null, AccessPath.getEmptyAccessPath(), source, null,
                        path, pathAccessPaths, new Stmt[path.length], true));
        return method.getSignature();
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
        int abortRequests;
        int taintRuns;
        long timeoutAtConstruction;
        Runnable duringRun = () -> { };

        StubApplication(InfoflowAndroidConfiguration configuration, boolean failCallGraph) {
            super(configuration);
            this.failCallGraph = failCallGraph;
        }

        @Override
        public void constructCallgraph() {
            timeoutAtConstruction = getConfig().getDataFlowTimeout();
            if (failCallGraph) {
                throw new IllegalStateException("call graph construction failed");
            }
            callGraphConstructions++;
        }

        @Override
        public InfoflowResults runInfoflow(Set<AndroidMethod> sources, Set<AndroidMethod> sinks)
                throws IOException {
            taintRuns++;
            duringRun.run();
            if (failure != null) {
                throw failure;
            }
            return results;
        }

        @Override
        public void abortAnalysis() {
            abortRequests++;
        }
    }
}
