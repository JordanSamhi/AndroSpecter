package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.config.SootConfig;
import com.jordansamhi.androspecter.analysis.InitializationMode;
import com.jordansamhi.androspecter.printers.Writer;
import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import com.jordansamhi.androspecter.result.TaintEndpoint;
import com.jordansamhi.androspecter.result.TaintFinding;
import com.jordansamhi.androspecter.result.TaintPathStep;
import com.jordansamhi.androspecter.result.TaintResultFormatter;
import soot.G;
import soot.Main;
import soot.PackManager;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.AndroidLibraryClassPatcher;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.jimple.infoflow.results.InfoflowResults;
import soot.jimple.infoflow.results.ResultSinkInfo;
import soot.jimple.infoflow.results.ResultSourceInfo;
import soot.jimple.infoflow.results.AbstractResultSourceSinkInfo;
import soot.options.Options;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/*-
 * #%L
 * AndroSpecter
 *
 * %%
 * Copyright (C) 2023 Jordan Samhi
 * All rights reserved
 *
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

/**
 * Low-level initialization and taint-analysis helper for FlowDroid Android analyses.
 * Initialization resets Soot's global state. Callers must exclusively manage that state
 * throughout initialization, execution, and result detachment; this helper does not detect
 * external resets or coordinate multiple analyses. Prefer
 * {@link com.jordansamhi.androspecter.analysis.AndroidAnalysis} for a managed lifecycle.
 * <p>
 * Apart from {@link #abortAnalysis()}, operations must not run concurrently on this instance.
 * Use one owning thread for initialization and subsequent backend access. None of these operations
 * acquires the managed facade's exclusive-session lease, so separate helper instances cannot safely
 * analyze different APKs concurrently in the same JVM.
 * Returned {@link SetupApplication} objects are live backend objects, whereas
 * {@link TaintAnalysisResult} values and legacy report strings are detached and remain
 * usable after Soot is reset.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class FlowdroidUtils {

    private String packageName;
    private boolean manifestRead;
    private final String apkPath;
    private volatile SetupApplication sa;
    private volatile boolean initialized;
    private volatile boolean abortRequested;

    /**
     * Creates a helper for an APK without opening it or initializing Soot.
     * Path existence and APK contents are checked by the backend when used.
     *
     * @param apkPath non-null path to the APK to analyze
     * @throws NullPointerException if {@code apkPath} is null
     */
    public FlowdroidUtils(String apkPath) {
        this.apkPath = Objects.requireNonNull(apkPath, "apkPath");
    }

    /**
     * Resets Soot and constructs the APK's Android entry points and call graph with default
     * FlowDroid options. Code elimination is disabled and precise path reconstruction is enabled.
     * This is equivalent to the customizer overload with a no-op customizer; see that overload
     * for lifecycle and cancellation details.
     *
     * @param platformPath non-null Android SDK platforms directory or a specific {@code android.jar}
     * @param config Soot-options callback, or null to use this library's {@link SootConfig}
     * @param callGraphAlgo {@code CHA}, {@code RTA}, {@code VTA}, or {@code SPARK}, ignoring
     *                      surrounding whitespace and case; null selects {@code CHA}
     * @return live, mutable backend application bound to the current Soot state
     * @throws NullPointerException if {@code platformPath} is null
     * @throws IllegalArgumentException if the call graph algorithm is unsupported
     * @throws CancellationException if cancellation or interruption is observed during initialization
     * @throws RuntimeException if backend initialization or call graph construction fails
     * @see #initializeFlowdroid(String, IInfoflowConfig, String, Consumer)
     */
    public SetupApplication initializeFlowdroid(String platformPath, IInfoflowConfig config, String callGraphAlgo) {
        return initializeFlowdroid(platformPath, config, callGraphAlgo, configuration -> { });
    }

    /**
     * Resets Soot and constructs an analysis after applying a synchronous configuration customizer.
     * Defaults disable code elimination, enable precise path reconstruction, and select the requested
     * call graph algorithm. The customizer runs after those defaults and before backend creation,
     * so it may override them or configure callback, data-flow, and path-reconstruction limits.
     * For example, {@code configuration -> configuration.setDataFlowTimeout(60)} sets the
     * backend data-flow timeout in seconds.
     * <p>The customizer receives the actual configuration retained by the backend, not a defensive copy.
     * Do not mutate a retained configuration while analysis is running. This low-level callback is not
     * subject to the managed builder's protected-setting checks; resetting Soot or changing global
     * FlowDroid settings in it is the caller's responsibility. The separate {@code config} callback runs
     * during Soot setup and can override Soot options; it is not the Android configuration customizer.
     * <p>
     * Every initialization attempt discards this helper's previous initialization and clears
     * its cancellation request. Once construction succeeds, subsequent taint runs reuse the
     * current Soot instance. A failed attempt leaves this helper uninitialized and does not
     * restore the previous scene. The caller must prevent other Soot users from resetting or
     * mutating the scene until its operations finish. Cancellation is checked before and after
     * call graph construction; it does not guarantee immediate backend termination.
     * The returned configuration's integration mode is set to {@code UseExistingInstance} after success.
     * Reinitialization is destructive; initialization does not close or restore a previous analysis.
     *
     * @param platformPath non-null Android SDK platforms directory or a specific {@code android.jar}
     * @param config Soot-options callback, or null to use this library's {@link SootConfig}
     * @param callGraphAlgo {@code CHA}, {@code RTA}, {@code VTA}, or {@code SPARK}, ignoring
     *                      surrounding whitespace and case; null selects {@code CHA}
     * @param customizer non-null callback receiving the live mutable backend configuration
     * @return live, mutable backend application bound to the current Soot state
     * @throws NullPointerException if {@code platformPath} or {@code customizer} is null
     * @throws IllegalArgumentException if the call graph algorithm is unsupported
     * @throws CancellationException if cancellation or interruption is observed during initialization
     * @throws RuntimeException if the customizer, backend initialization, or call graph construction fails
     */
    public SetupApplication initializeFlowdroid(String platformPath, IInfoflowConfig config, String callGraphAlgo,
                                               Consumer<InfoflowAndroidConfiguration> customizer) {
        return initializeFlowdroidInternal(platformPath, config, callGraphAlgo, customizer, false);
    }

    /**
     * Initializes either an eager Android analysis or a class-only scene for later promotion.
     * Class-only initialization loads APK classes with the configured Android/additional classpath,
     * runs Soot's {@code wjpp} preparation pack and patches framework models, but does not discover
     * callbacks or build a graph. It does not eagerly retrieve every application method body; later
     * inspection or analysis may still trigger body loading.
     * The returned backend uses the existing scene when constructing a graph or running taint later,
     * preserving bodies modified in the meantime. This method otherwise shares the customizer
     * overload's defaults, ownership, and cancellation contracts.
     * Class-only customization must retain {@code CreateNewInstance} until loading finishes;
     * this method then switches the backend to {@code UseExistingInstance}. A null mode is rejected
     * before changing this helper's existing initialization state.
     *
     * @param platformPath non-null Android platforms directory or platform JAR
     * @param config Soot-options callback, or null for this library's defaults
     * @param callGraphAlgo supported algorithm, or null for CHA
     * @param customizer non-null backend configuration callback applied before loading
     * @param mode non-null amount of initialization to perform
     * @return live backend application bound to the newly initialized Soot scene
     * @throws NullPointerException if a required argument is null
     * @throws IllegalArgumentException if the algorithm or class-only integration mode is unsupported
     * @throws CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if customization, class loading, or graph construction fails
     */
    public SetupApplication initializeFlowdroid(String platformPath, IInfoflowConfig config, String callGraphAlgo,
                                               Consumer<InfoflowAndroidConfiguration> customizer,
                                               InitializationMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode == InitializationMode.EAGER) {
            return initializeFlowdroid(platformPath, config, callGraphAlgo, customizer);
        }
        return initializeFlowdroidInternal(platformPath, config, callGraphAlgo, customizer, true);
    }

    private SetupApplication initializeFlowdroidInternal(String platformPath, IInfoflowConfig config,
            String callGraphAlgo, Consumer<InfoflowAndroidConfiguration> customizer, boolean classesOnly) {
        this.initialized = false;
        this.sa = null;
        this.abortRequested = false;
        Objects.requireNonNull(platformPath, "platformPath");
        Objects.requireNonNull(customizer, "customizer");
        InfoflowConfiguration.CallgraphAlgorithm cgAlgo;
        switch (callGraphAlgo == null ? "CHA" : callGraphAlgo.trim().toUpperCase(Locale.ROOT)) {
            case "CHA": cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.CHA; break;
            case "RTA": cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.RTA; break;
            case "VTA": cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.VTA; break;
            case "SPARK": cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.SPARK; break;
            default: throw new IllegalArgumentException("Unsupported call graph algorithm: " + callGraphAlgo);
        }
        G.reset();
        InfoflowAndroidConfiguration ifac = new InfoflowAndroidConfiguration();
        ifac.getAnalysisFileConfig().setAndroidPlatformDir(new File(platformPath));
        ifac.getAnalysisFileConfig().setTargetAPKFile(new File(this.apkPath));
        ifac.setCodeEliminationMode(InfoflowConfiguration.CodeEliminationMode.NoCodeElimination);
        ifac.getPathConfiguration().setPathReconstructionMode(InfoflowConfiguration.PathReconstructionMode.Precise);
        ifac.setCallgraphAlgorithm(cgAlgo);
        customizer.accept(ifac);
        if (classesOnly && ifac.getSootIntegrationMode()
                != InfoflowConfiguration.SootIntegrationMode.CreateNewInstance) {
            throw new IllegalArgumentException("Class-only initialization must create its own Soot scene");
        }
        SetupApplication sa = createSetupApplication(ifac);
        IInfoflowConfig sootConfig = config == null ? new SootConfig() : config;
        sa.setSootConfig(sootConfig);
        this.sa = sa;
        try {
            checkInitializationCancellation(sa);
            if (classesOnly) {
                loadSootClasses(ifac, sootConfig);
            } else {
                sa.constructCallgraph();
            }
            checkInitializationCancellation(sa);
            ifac.setSootIntegrationMode(InfoflowAndroidConfiguration.SootIntegrationMode.UseExistingInstance);
            this.initialized = true;
            return sa;
        } catch (RuntimeException | Error failure) {
            this.sa = null;
            throw failure;
        }
    }

    SetupApplication createSetupApplication(InfoflowAndroidConfiguration configuration) {
        return new ReusableSetupApplication(configuration);
    }

    // FlowDroid 2.15.1 keeps initializeSoot private. Mirror its loading/preparation steps
    // without calling constructCallgraph (which would perform Android callback discovery).
    void loadSootClasses(InfoflowAndroidConfiguration configuration, IInfoflowConfig sootConfig) {
        File apk = configuration.getAnalysisFileConfig().getTargetAPKFile();
        File android = configuration.getAnalysisFileConfig().getAndroidPlatformDir();
        Options options = Options.v();
        options.set_no_bodies_for_excluded(true);
        options.set_allow_phantom_refs(true);
        options.set_output_format(configuration.getWriteOutputFiles()
                ? Options.output_format_jimple : Options.output_format_none);
        options.set_whole_program(true);
        options.set_process_dir(List.of(apk.getAbsolutePath()));
        if (android.isFile()) {
            options.set_force_android_jar(android.getAbsolutePath());
        } else {
            options.set_android_jars(android.getAbsolutePath());
        }
        options.set_src_prec(Options.src_prec_apk_class_jimple);
        options.set_keep_offset(false);
        options.set_keep_line_number(configuration.getEnableLineNumbers());
        options.set_throw_analysis(Options.throw_analysis_dalvik);
        options.set_ignore_resolution_errors(true);
        if (configuration.getEnableOriginalNames()) {
            options.setPhaseOption("jb", "use-original-names:true");
        }
        sootConfig.setSootOptions(options, configuration);
        String classpath = android.isFile() ? android.getAbsolutePath()
                : Scene.v().getAndroidJarPath(android.getAbsolutePath(), apk.getAbsolutePath());
        String additional = configuration.getAnalysisFileConfig().getAdditionalClasspath();
        if (additional != null && !additional.isEmpty()) {
            classpath += File.pathSeparator + additional;
        }
        options.set_soot_classpath(classpath);
        Main.v().autoSetOptions();
        Scene.v().loadNecessaryClasses();
        PackManager.v().getPack("wjpp").apply();
        new AndroidLibraryClassPatcher().patchLibraries();
    }

    /**
     * Requests best-effort cancellation from the analysis thread or another thread.
     * The request is recorded even when no backend is currently running, and subsequent
     * taint calls return a cancelled result until reinitialization clears the request.
     * When a backend exists, its abort method is also invoked.
     * <p>
     * FlowDroid cannot necessarily interrupt every callback or call graph phase immediately.
     * This method does not wait for termination; callers must keep ownership of Soot until
     * the running operation returns, even after requesting cancellation.
     */
    public void abortAnalysis() {
        abortRequested = true;
        SetupApplication current = sa;
        if (current != null) {
            current.abortAnalysis();
        }
    }

    private void checkInitializationCancellation(SetupApplication application) {
        if (abortRequested || Thread.currentThread().isInterrupted()) {
            application.abortAnalysis();
            throw new CancellationException("FlowDroid initialization cancelled");
        }
    }

    /**
     * Runs taint analysis synchronously and renders successful findings as legacy reports.
     * Failed, timed-out, or cancelled analyses throw; use
     * {@link #runTaintAnalysisResult(Set, Set)} to inspect their partial findings and diagnostics.
     * The caller must preserve exclusive ownership of the initialized Soot scene and must
     * not mutate the source/sink descriptors during execution.
     * Analysis can construct or replace call graphs and mutate live method bodies; previously captured
     * Soot graph/body references must not be treated as an unchanged model after the call.
     *
     * @param sources non-null set of non-null source method descriptors; may be empty
     * @param sinks non-null set of non-null sink method descriptors; may be empty
     * @return mutable independent set of detached report strings, with identical reports
     *         deduplicated; empty only for a successful analysis with no findings
     * @throws NullPointerException if {@code sources} or {@code sinks} is null
     * @throws IllegalStateException if initialization has not succeeded or the analysis result
     *                               is failed, timed out, cancelled, or unavailable
     */
    public Set<String> runTaintAnalysis(Set<AndroidMethod> sources, Set<AndroidMethod> sinks) {
        AnalysisExecution execution = executeTaintAnalysis(sources, sinks, UUID.randomUUID());
        try {
            Set<String> reports = TaintResultFormatter.format(execution.result());
            if (reports.isEmpty()) {
                Writer.v().pwarning("No Flow found.");
            }
            return new LinkedHashSet<>(reports);
        } catch (IllegalStateException failure) {
            if (execution.failure() != null) {
                failure.initCause(execution.failure());
            }
            throw failure;
        }
    }

    /**
     * Runs synchronous taint analysis with a new random identifier and returns detached data.
     * Check the returned status before interpreting an empty finding list. This overload has
     * the same lifecycle, partial-result, and cancellation behavior as the identifier overload.
     *
     * @param sources non-null set of non-null source method descriptors; may be empty
     * @param sinks non-null set of non-null sink method descriptors; may be empty
     * @return non-null immutable result with a newly generated analysis identifier
     * @throws NullPointerException if {@code sources} or {@code sinks} is null
     * @throws IllegalStateException if this helper has not been successfully initialized
     * @see #runTaintAnalysisResult(Set, Set, UUID)
     */
    public TaintAnalysisResult runTaintAnalysisResult(Set<AndroidMethod> sources, Set<AndroidMethod> sinks) {
        return runTaintAnalysisResult(sources, sinks, UUID.randomUUID());
    }

    /**
     * Runs synchronous taint analysis associated with the caller's identifier and detaches its result.
     * Source/sink descriptors are passed to FlowDroid without copying; do not mutate them during
     * the call. The caller must preserve exclusive ownership of the initialized Soot scene until
     * result detachment finishes. This low-level API does not detect an external Soot reset.
     * Running FlowDroid can rebuild the call graph and rewrite bodies in that scene. Detach any earlier
     * results needed for comparison before execution; this helper does not invalidate facade caches.
     * <p>
     * Findings returned by the backend are retained for failed, timed-out, or cancelled runs
     * when they can be detached. A backend exception before a result is returned yields no
     * findings. Detachment failures retain findings already copied and add a diagnostic.
     * A missing reconstructed path does not remove a finding. Endpoint and path values are
     * copied to strings, so the returned result remains usable after reset.
     * <p>
     * Backend exceptions are represented as {@link AnalysisStatus#FAILED}, or
     * {@link AnalysisStatus#CANCELLED} when cancellation/interruption is observed; backend
     * timeout states become {@link AnalysisStatus#TIMED_OUT}. Cancellation requested before
     * execution returns a cancelled empty result without invoking the backend. JVM errors
     * are not converted to result statuses. Only a successful status with no findings denotes
     * a completed empty analysis; success does not guarantee analysis soundness.
     *
     * @param sources non-null set of non-null source method descriptors; may be empty
     * @param sinks non-null set of non-null sink method descriptors; may be empty
     * @param analysisId non-null identifier to copy into the result; uniqueness is caller-managed
     * @return non-null immutable detached result with completion status, findings, and diagnostics
     * @throws NullPointerException if a set or {@code analysisId} is null
     * @throws IllegalStateException if this helper has not been successfully initialized
     */
    public TaintAnalysisResult runTaintAnalysisResult(Set<AndroidMethod> sources, Set<AndroidMethod> sinks,
                                                    UUID analysisId) {
        return executeTaintAnalysis(sources, sinks, analysisId).result();
    }

    private AnalysisExecution executeTaintAnalysis(Set<AndroidMethod> sources, Set<AndroidMethod> sinks,
                                                   UUID analysisId) {
        SetupApplication application = this.sa;
        if (!initialized || application == null) {
            throw new IllegalStateException("The SetupApplication has not been initialized.\n"
                    + "First call the initializeFlowdroid method.");
        }
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(sinks, "sinks");
        Objects.requireNonNull(analysisId, "analysisId");
        if (abortRequested || Thread.currentThread().isInterrupted()) {
            return new AnalysisExecution(new TaintAnalysisResult(analysisId, AnalysisStatus.CANCELLED,
                    List.of(), List.of("Analysis cancelled before execution")), null);
        }
        InfoflowResults results;
        try {
            results = application.runInfoflow(sources, sinks);
        } catch (Exception failure) {
            boolean cancelled = abortRequested || Thread.currentThread().isInterrupted()
                    || failure instanceof CancellationException || failure instanceof InterruptedException;
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new AnalysisExecution(new TaintAnalysisResult(analysisId,
                    cancelled ? AnalysisStatus.CANCELLED : AnalysisStatus.FAILED, List.of(),
                    List.of("FlowDroid taint analysis failed for " + apkPath + ": " + failure)), failure);
        }
        List<String> diagnostics = new ArrayList<>();
        boolean cancelled = abortRequested || Thread.currentThread().isInterrupted();
        if (results == null) {
            diagnostics.add("FlowDroid returned no analysis result for " + apkPath);
            return new AnalysisExecution(new TaintAnalysisResult(analysisId,
                    cancelled ? AnalysisStatus.CANCELLED : AnalysisStatus.FAILED, List.of(), diagnostics), null);
        }
        if (results.getExceptions() != null) {
            diagnostics.addAll(results.getExceptions());
        }
        int termination = results.getTerminationState();
        AnalysisStatus status;
        if (cancelled) {
            status = AnalysisStatus.CANCELLED;
            diagnostics.add("Analysis cancellation requested");
        } else if (results.wasAbortedTimeout()) {
            status = AnalysisStatus.TIMED_OUT;
        } else if (termination != InfoflowResults.TERMINATION_SUCCESS || !diagnostics.isEmpty()) {
            status = AnalysisStatus.FAILED;
        } else {
            status = AnalysisStatus.SUCCESS;
        }
        if (termination != InfoflowResults.TERMINATION_SUCCESS) {
            diagnostics.add("FlowDroid termination state: " + termination);
        }
        List<TaintFinding> findings = new ArrayList<>();
        Exception detachmentFailure = null;
        try {
            if (!results.isEmpty()) {
                Map<Unit, String> owners = activeStatementOwners(results);
                for (ResultSinkInfo sink : results.getResults().keySet()) {
                    TaintEndpoint sinkEndpoint = detachEndpoint(sink, owners);
                    for (ResultSourceInfo source : results.getResults().get(sink)) {
                        List<TaintPathStep> steps = new ArrayList<>();
                        Stmt[] path = source.getPath();
                        if (path != null) {
                            for (Stmt statement : path) {
                                steps.add(new TaintPathStep(owners.get(statement), statement.toString()));
                            }
                        }
                        findings.add(new TaintFinding(detachEndpoint(source, owners), sinkEndpoint,
                                steps, path != null));
                    }
                }
            }
        } catch (RuntimeException failure) {
            // Keep already detached findings, but do not report a failed conversion as a complete result.
            detachmentFailure = failure;
            diagnostics.add("Could not detach all taint findings: " + failure);
            if (status == AnalysisStatus.SUCCESS) {
                status = AnalysisStatus.FAILED;
            }
        }
        findings.sort(Comparator.comparing((TaintFinding finding) -> finding.source().description())
                .thenComparing(finding -> finding.source().methodSignature(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(finding -> finding.sink().description())
                .thenComparing(finding -> finding.sink().methodSignature(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(finding -> finding.path().toString()));
        return new AnalysisExecution(new TaintAnalysisResult(analysisId, status, findings, diagnostics),
                detachmentFailure);
    }

    private Map<Unit, String> activeStatementOwners(InfoflowResults results) {
        // Index only statements that occur in the result, rather than retaining every body unit.
        Map<Unit, String> owners = new IdentityHashMap<>();
        for (ResultSinkInfo sink : results.getResults().keySet()) {
            if (sink.getStmt() != null) {
                owners.put(sink.getStmt(), null);
            }
            for (ResultSourceInfo source : results.getResults().get(sink)) {
                if (source.getStmt() != null) {
                    owners.put(source.getStmt(), null);
                }
                if (source.getPath() != null) {
                    for (Stmt statement : source.getPath()) {
                        owners.put(statement, null);
                    }
                }
            }
        }
        int unresolved = owners.size();
        for (SootClass clazz : Scene.v().getClasses()) {
            if (unresolved == 0) {
                break;
            }
            if (clazz.resolvingLevel() < SootClass.SIGNATURES) {
                continue;
            }
            for (SootMethod method : clazz.getMethods()) {
                if (method.hasActiveBody()) {
                    for (Unit statement : method.getActiveBody().getUnits()) {
                        if (owners.containsKey(statement) && owners.get(statement) == null) {
                            owners.put(statement, method.getSignature());
                            unresolved--;
                        }
                    }
                }
            }
        }
        return owners;
    }

    private TaintEndpoint detachEndpoint(AbstractResultSourceSinkInfo endpoint, Map<Unit, String> owners) {
        Stmt statement = endpoint.getStmt();
        return new TaintEndpoint(endpoint.toString(), owners.get(statement),
                statement == null ? null : statement.toString(),
                statement != null && statement.containsInvokeExpr()
                        ? statement.getInvokeExpr().getMethodRef().getSignature() : null,
                endpoint.getAccessPath() == null ? null : endpoint.getAccessPath().toString());
    }

    // The exception is retained only for the legacy synchronous call's cause, never in the public result.
    private record AnalysisExecution(TaintAnalysisResult result, Exception failure) {
    }

    /**
     * Reads the manifest package name lazily, independently of FlowDroid initialization.
     * The first result, including a failed read returning null, is cached for this helper's
     * lifetime. Read failures are logged as warnings and are not retried by later calls.
     * Reinitializing analysis or replacing the APK file does not invalidate this cache. The manifest
     * reader is closed before return. FlowDroid's binary-XML parser can consult Soot to resolve unnamed
     * attributes; use {@link com.jordansamhi.androspecter.metadata.ApkInfo#read(java.nio.file.Path)}
     * when metadata reading must leave Soot untouched.
     *
     * @return detached manifest package name, or null if it is unavailable or the manifest could not be processed
     */
    public String getPackageName() {
        if (!manifestRead) {
            manifestRead = true;
            try (ProcessManifest manifest = new ProcessManifest(this.apkPath)) {
                packageName = manifest.getPackageName();
            } catch (Exception e) {
                Writer.v().pwarning("Cannot read APK manifest: " + e.getMessage());
            }
        }
        return packageName;
    }
}
