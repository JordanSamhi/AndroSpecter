package com.jordansamhi.androspecter.analysis;

import com.jordansamhi.androspecter.FlowdroidUtils;
import com.jordansamhi.androspecter.execution.CancellationSource;
import com.jordansamhi.androspecter.execution.CancellationToken;
import com.jordansamhi.androspecter.files.SourceSinkConfiguration;
import com.jordansamhi.androspecter.inspection.Inspection;
import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.jimple.toolkits.callgraph.CallGraph;

import java.nio.file.Files;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AndroidAnalysisTest {
    @TempDir Path directory;
    Path apk;

    @BeforeEach
    void setup() throws Exception {
        G.reset();
        apk = Files.createFile(directory.resolve("test.apk"));
    }

    @AfterEach
    void reset() {
        G.reset();
    }

    @Test
    void rejectsOverlappingSessionsWithoutResettingActiveScene() {
        var builder = AndroidAnalysis.builder(apk, directory);
        try (AndroidAnalysis first = builder.openWith(new Backend())) {
            G generation = G.v();
            assertThrows(IllegalStateException.class, () -> builder.openWith(new Backend()));
            assertSame(generation, G.v());
            first.context().checkValid();
        }
        try (AndroidAnalysis second = builder.openWith(new Backend())) {
            second.context().checkValid();
        }
    }

    @Test
    void failedOpenReleasesSessionOwnership() {
        Backend failing = new Backend();
        failing.failInitialization = true;
        assertThrows(IllegalStateException.class,
                () -> AndroidAnalysis.builder(apk, directory).openWith(failing));
        try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
            assertNotNull(next.id());
        }
    }

    private CancellationToken throwingRegistration() {
        return new CancellationToken() {
            @Override public boolean isCancellationRequested() { return false; }
            @Override public Registration onCancellation(Runnable action) {
                return () -> { throw new IllegalStateException("registration cleanup failed"); };
            }
        };
    }

    @Test
    void callerCleanupFailureDoesNotLeakOwnershipOrHideInitializationFailure() {
        Backend backend = new Backend();
        backend.failInitialization = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AndroidAnalysis.builder(apk, directory).cancellationToken(throwingRegistration()).openWith(backend));
        assertEquals("initialization failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
            next.context().checkValid();
        }
    }

    @Test
    void closeResetsOwnedStateAndReleasesLeaseEvenWhenCallerRegistrationThrows() {
        AndroidAnalysis analysis = AndroidAnalysis.builder(apk, directory)
                .cancellationToken(throwingRegistration()).openWith(new Backend());
        G generation = G.v();
        assertThrows(IllegalStateException.class, analysis::close);
        assertNotSame(generation, G.v());
        try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
            next.context().checkValid();
        }
    }

    @Test
    void cancellationBetweenInitialCheckAndConfigurationPreventsBackendWork() {
        try (CancellationSource source = new CancellationSource()) {
            Backend backend = new Backend();
            backend.beforeConfiguration = source::cancel;
            assertThrows(CancellationException.class, () -> AndroidAnalysis.builder(apk, directory)
                    .cancellationToken(source.token()).openWith(backend));
            assertFalse(backend.initializationCompleted);
        }
    }

    @Test
    void closePreservesAnExternallyReplacedSceneAndSnapshotsSurvive() {
        AndroidAnalysis analysis = AndroidAnalysis.builder(apk, directory).openWith(new Backend());
        var snapshot = analysis.callGraph();
        G.reset();
        G replacement = G.v();
        assertThrows(IllegalStateException.class, analysis::callGraph);
        analysis.close();
        analysis.close();
        assertSame(replacement, G.v());
        assertEquals(analysis.id(), snapshot.analysisId());
        assertTrue(snapshot.edges().isEmpty());
    }

    @Test
    void closingBorrowedContextStillRequiresOwnerCloseToResetAndReleaseSession() {
        AndroidAnalysis analysis = AndroidAnalysis.builder(apk, directory).openWith(new Backend());
        G generation = G.v();
        analysis.context().close();
        assertThrows(IllegalStateException.class, analysis::callGraph);
        assertThrows(IllegalStateException.class,
                () -> AndroidAnalysis.builder(apk, directory).openWith(new Backend()));
        analysis.close();
        assertNotSame(generation, G.v());
        try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
            next.context().checkValid();
        }
    }

    @Test
    void transformedBodiesInvalidateTheGraphAndSnapshotsRemainDetached() {
        Backend backend = new Backend();
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(apk, directory).openWith(backend)) {
            var before = analysis.callGraph();
            assertSame(before, analysis.callGraph());
            analysis.apply(context -> context.checkValid());
            assertNotSame(before, analysis.callGraph());
            assertEquals(1, backend.rebuilds);
            assertTrue(before.edges().isEmpty());
        }
    }

    @Test
    void failedTransformAlsoInvalidatesGraph() {
        Backend backend = new Backend();
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(apk, directory).openWith(backend)) {
            analysis.callGraph();
            assertThrows(IllegalArgumentException.class, () -> analysis.apply(context -> {
                throw new IllegalArgumentException("partially applied");
            }));
            analysis.callGraph();
            assertEquals(1, backend.rebuilds);
        }
    }

    @Test
    void metricsAndSourceSinkConfigurationAreInstanceOwned() {
        var configuration = SourceSinkConfiguration.builder().source("<example.C: java.lang.String read()>").build();
        Backend firstBackend = new Backend();
        try (AndroidAnalysis first = AndroidAnalysis.builder(apk, directory)
                .sourcesAndSinks(configuration).openWith(firstBackend)) {
            first.metrics().incrementMetric("calls");
            assertEquals(AnalysisStatus.SUCCESS, first.analyzeTaint().status());
            assertEquals(1, firstBackend.sources.size());
            assertSame(configuration, first.sourcesAndSinks());
        }
        try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
            assertEquals(0, next.metrics().getMetric("calls"));
            assertTrue(next.sourcesAndSinks().sources().isEmpty());
        }
    }

    @Test
    void cancellationSignalsBackendAndReturnsExplicitCancelledStatus() {
        Backend backend = new Backend();
        try (CancellationSource source = new CancellationSource();
             AndroidAnalysis analysis = AndroidAnalysis.builder(apk, directory)
                     .cancellationToken(source.token()).openWith(backend)) {
            source.cancel();
            assertTrue(backend.aborted);
            assertThrows(CancellationException.class, analysis::callGraph);
            assertEquals(AnalysisStatus.CANCELLED, analysis.analyzeTaint().status());
            assertEquals(0, backend.taintRuns);
        }
    }

    @Test
    void alreadyCancelledOpenDoesNotResetExistingSootState() {
        G current = G.v();
        try (CancellationSource source = new CancellationSource()) {
            source.cancel();
            assertThrows(CancellationException.class, () -> AndroidAnalysis.builder(apk, directory)
                    .cancellationToken(source.token()).openWith(new Backend()));
            assertSame(current, G.v());
        }
    }

    @Test
    void cancellationDuringRegistrationDoesNotResetCallerScene() {
        G generation = G.v();
        try (CancellationSource source = new CancellationSource()) {
            CancellationToken token = new CancellationToken() {
                @Override public boolean isCancellationRequested() {
                    return source.token().isCancellationRequested();
                }
                @Override public Registration onCancellation(Runnable action) {
                    Registration registration = source.token().onCancellation(action);
                    source.cancel();
                    return registration;
                }
            };
            assertThrows(CancellationException.class, () -> AndroidAnalysis.builder(apk, directory)
                    .cancellationToken(token).openWith(new Backend()));
            assertSame(generation, G.v());
        }
        try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
            next.context().checkValid();
        }
    }

    @Test
    void forwardsValidatedTimeoutsBeforeCallGraphConstruction() {
        Backend backend = new Backend();
        try (AndroidAnalysis ignored = AndroidAnalysis.builder(apk, directory)
                .callbackTimeout(Duration.ofMillis(1)).taintTimeout(Duration.ofSeconds(2))
                .pathTimeout(Duration.ofMillis(2500)).openWith(backend)) {
            assertEquals(1, backend.configuration.getCallbackConfig().getCallbackAnalysisTimeout());
            assertEquals(2, backend.configuration.getDataFlowTimeout());
            assertEquals(3, backend.configuration.getPathConfiguration().getPathReconstructionTimeout());
        }
        assertThrows(IllegalArgumentException.class,
                () -> AndroidAnalysis.builder(apk, directory).taintTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> AndroidAnalysis.builder(apk, directory).pathTimeout(Duration.ofDays(100000)));
    }

    @Test
    void classOnlyInspectionDefersGraphAndPreservesChangesOnFirstCapture() {
        Backend backend = new Backend();
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(apk, directory)
                .initializationMode(InitializationMode.CLASSES_ONLY).openWith(backend)) {
            assertFalse(Scene.v().hasCallGraph());
            G generation = G.v();
            SootClass added = new SootClass("example.Added");
            analysis.apply(context -> Scene.v().addClass(added));
            analysis.inspect(Inspection.builder().onClass(clazz ->
                    analysis.metrics().incrementMetric("classes")).build());
            assertEquals(1, analysis.metrics().getMetric("classes"));
            assertEquals(0, backend.rebuilds);
            var graph = analysis.callGraph();
            assertEquals(1, backend.rebuilds);
            assertSame(generation, G.v());
            assertSame(added, Scene.v().getSootClass("example.Added"));
            assertSame(graph, analysis.callGraph());
            assertThrows(IllegalStateException.class,
                    () -> AndroidAnalysis.builder(apk, directory).openWith(new Backend()));
        }
    }

    @Test
    void typedSettingsApplyBeforeAdvancedOverridesAndRetainedConfigIsIsolated() throws Exception {
        Path extra = Files.createFile(directory.resolve("dependency.jar"));
        Path[] entries = {extra, directory, extra};
        AtomicReference<InfoflowAndroidConfiguration> retained = new AtomicReference<>();
        var builder = AndroidAnalysis.builder(apk, directory)
                .pathReconstruction(PathReconstructionMode.NONE)
                .callbacks(new CallbackOptions(false, CallbackOptions.Analyzer.FAST, 17, 3))
                .additionalClasspath(entries)
                .configureFlowDroid(configuration -> {
                    assertEquals(InfoflowConfiguration.PathReconstructionMode.NoPaths,
                            configuration.getPathConfiguration().getPathReconstructionMode());
                    assertFalse(configuration.getCallbackConfig().getEnableCallbacks());
                    assertEquals(17, configuration.getCallbackConfig().getMaxCallbacksPerComponent());
                    assertEquals(3, configuration.getCallbackConfig().getMaxAnalysisCallbackDepth());
                    assertEquals(InfoflowAndroidConfiguration.CallbackAnalyzer.Fast,
                            configuration.getCallbackConfig().getCallbackAnalyzer());
                    configuration.setEnableReflection(true);
                    configuration.getPathConfiguration().setPathReconstructionMode(
                            InfoflowConfiguration.PathReconstructionMode.Fast);
                    retained.set(configuration);
                });
        entries[0] = directory.resolve("missing.jar");
        Backend backend = new Backend();
        try (AndroidAnalysis analysis = builder.openWith(backend)) {
            assertEquals(extra + File.pathSeparator + directory,
                    backend.configuration.getAnalysisFileConfig().getAdditionalClasspath());
            assertTrue(backend.configuration.getEnableReflection());
            retained.get().setEnableReflection(false);
            retained.get().getAnalysisFileConfig().setTargetAPKFile(new File("changed.apk"));
            retained.get().getPathConfiguration().setPathReconstructionMode(
                    InfoflowConfiguration.PathReconstructionMode.NoPaths);
            assertEquals(apk.toFile(), backend.configuration.getAnalysisFileConfig().getTargetAPKFile());
            assertEquals(InfoflowConfiguration.PathReconstructionMode.Fast,
                    backend.configuration.getPathConfiguration().getPathReconstructionMode());
            assertTrue(backend.configuration.getEnableReflection());
            analysis.analyzeTaint();
            analysis.analyzeTaint();
            assertTrue(backend.configuration.getEnableReflection());
            assertEquals(2, backend.taintRuns);
        }
        try (AndroidAnalysis ignored = builder.openWith(new Backend())) {
            // A reused builder starts from typed settings, not the previously retained object.
        }
    }

    @Test
    void rejectsAdvancedChangesToManagedInputsAndOwnershipAndReleasesLease() {
        List<Consumer<InfoflowAndroidConfiguration>> invalid = List.of(
                configuration -> configuration.getAnalysisFileConfig().setTargetAPKFile(new File("different.apk")),
                configuration -> configuration.getAnalysisFileConfig().setAndroidPlatformDir(new File("other-sdk")),
                configuration -> configuration.getAnalysisFileConfig().setSourceSinkFile(new File("other-sources")),
                configuration -> configuration.setSootIntegrationMode(
                        InfoflowConfiguration.SootIntegrationMode.UseExistingCallgraph),
                configuration -> configuration.setTaintAnalysisEnabled(false));
        for (var customizer : invalid) {
            Backend backend = new Backend();
            assertThrows(IllegalArgumentException.class,
                    () -> AndroidAnalysis.builder(apk, directory).configureFlowDroid(customizer).openWith(backend));
            assertFalse(backend.initializationCompleted);
            try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
                next.context().checkValid();
            }
        }
    }

    @Test
    void customizerCannotReplaceSootOrLeakStaticConfiguration() {
        assertThrows(IllegalStateException.class, () -> AndroidAnalysis.builder(apk, directory)
                .configureFlowDroid(configuration -> G.reset()).openWith(new Backend()));
        boolean original = InfoflowConfiguration.getOneResultPerAccessPath();
        assertThrows(IllegalArgumentException.class, () -> AndroidAnalysis.builder(apk, directory)
                .configureFlowDroid(configuration -> InfoflowConfiguration.setOneResultPerAccessPath(!original))
                .openWith(new Backend()));
        assertEquals(original, InfoflowConfiguration.getOneResultPerAccessPath());
        try (AndroidAnalysis next = AndroidAnalysis.builder(apk, directory).openWith(new Backend())) {
            next.context().checkValid();
        }
    }

    @Test
    void validatesClasspathBeforeInitializationAndAfterAdvancedCustomization() {
        G generation = G.v();
        assertThrows(IllegalArgumentException.class, () -> AndroidAnalysis.builder(apk, directory)
                .additionalClasspath(directory.resolve("missing.jar")).openWith(new Backend()));
        assertSame(generation, G.v());
        assertThrows(IllegalArgumentException.class, () -> AndroidAnalysis.builder(apk, directory)
                .configureFlowDroid(configuration -> configuration.getAnalysisFileConfig()
                        .setAdditionalClasspath(directory + File.pathSeparator))
                .openWith(new Backend()));
        assertThrows(IllegalArgumentException.class, () -> AndroidAnalysis.builder(apk, directory)
                .additionalClasspath(directory.resolve("unsupported" + File.pathSeparator + "path")));
    }

    private final class Backend extends FlowdroidUtils {
        boolean failInitialization;
        boolean aborted;
        int rebuilds;
        int taintRuns;
        Runnable beforeConfiguration = () -> { };
        boolean initializationCompleted;
        Set<AndroidMethod> sources;
        InfoflowAndroidConfiguration configuration;

        Backend() {
            super(apk.toString());
        }

        @Override
        public SetupApplication initializeFlowdroid(String platforms, IInfoflowConfig sootConfig,
                String algorithm, Consumer<InfoflowAndroidConfiguration> customizer) {
            return initializeStub(customizer, false);
        }

        @Override
        public SetupApplication initializeFlowdroid(String platforms, IInfoflowConfig sootConfig,
                String algorithm, Consumer<InfoflowAndroidConfiguration> customizer, InitializationMode mode) {
            return initializeStub(customizer, mode == InitializationMode.CLASSES_ONLY);
        }

        private SetupApplication initializeStub(Consumer<InfoflowAndroidConfiguration> customizer, boolean classesOnly) {
            G.reset();
            if (failInitialization) {
                throw new IllegalStateException("initialization failed");
            }
            configuration = new InfoflowAndroidConfiguration();
            configuration.getAnalysisFileConfig().setTargetAPKFile(apk.toFile());
            configuration.getAnalysisFileConfig().setAndroidPlatformDir(directory.toFile());
            beforeConfiguration.run();
            customizer.accept(configuration);
            initializationCompleted = true;
            if (!classesOnly) {
                Scene.v().setCallGraph(new CallGraph());
            }
            configuration.setSootIntegrationMode(InfoflowConfiguration.SootIntegrationMode.UseExistingInstance);
            return new SetupApplication(configuration) {
                @Override public void constructCallgraph() {
                    rebuilds++;
                    Scene.v().setCallGraph(new CallGraph());
                }
            };
        }

        @Override
        public void abortAnalysis() {
            aborted = true;
        }

        @Override
        public TaintAnalysisResult runTaintAnalysisResult(Set<AndroidMethod> sources, Set<AndroidMethod> sinks,
                                                        UUID id) {
            this.sources = sources;
            taintRuns++;
            return new TaintAnalysisResult(id, AnalysisStatus.SUCCESS, List.of(), List.of());
        }
    }
}
