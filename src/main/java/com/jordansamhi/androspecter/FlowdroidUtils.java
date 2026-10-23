package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.config.SootConfig;
import com.jordansamhi.androspecter.printers.Writer;
import com.jordansamhi.androspecter.result.AnalysisStatus;
import com.jordansamhi.androspecter.result.TaintAnalysisResult;
import com.jordansamhi.androspecter.result.TaintEndpoint;
import com.jordansamhi.androspecter.result.TaintFinding;
import com.jordansamhi.androspecter.result.TaintPathStep;
import com.jordansamhi.androspecter.result.TaintResultFormatter;
import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.data.AndroidMethod;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.jimple.infoflow.results.InfoflowResults;
import soot.jimple.infoflow.results.ResultSinkInfo;
import soot.jimple.infoflow.results.ResultSourceInfo;
import soot.jimple.infoflow.results.AbstractResultSourceSinkInfo;

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
 * A utility class for initializing and running Flowdroid analyses on Android apps.
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

    public FlowdroidUtils(String apkPath) {
        this.apkPath = Objects.requireNonNull(apkPath, "apkPath");
    }

    /**
     * Initializes a Flowdroid analysis on an Android app.
     *
     * @param platformPath  The path to the directory containing the Android platform.
     * @param config        The configuration to be used for the analysis, or null to use default configuration.
     * @param callGraphAlgo The algorithm (CHA, RTA, VTA, or SPARK), case-insensitive; null selects CHA.
     * @return The SetupApplication object representing the analysis.
     */
    public SetupApplication initializeFlowdroid(String platformPath, IInfoflowConfig config, String callGraphAlgo) {
        return initializeFlowdroid(platformPath, config, callGraphAlgo, configuration -> { });
    }

    /**
     * Initializes an analysis with a customizer applied after defaults and before call graph construction.
     * The customizer may configure FlowDroid's callback, data-flow, and reconstruction timeouts.
     */
    public SetupApplication initializeFlowdroid(String platformPath, IInfoflowConfig config, String callGraphAlgo,
                                               Consumer<InfoflowAndroidConfiguration> customizer) {
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
        SetupApplication sa = createSetupApplication(ifac);
        if (config == null) {
            sa.setSootConfig(new SootConfig());
        } else {
            sa.setSootConfig(config);
        }
        this.sa = sa;
        try {
            checkInitializationCancellation(sa);
            sa.constructCallgraph();
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
        return new SetupApplication(configuration);
    }

    /**
     * Requests best-effort cancellation of the current analysis. FlowDroid cannot necessarily
     * interrupt every callback or call graph phase immediately; callers must keep ownership
     * of Soot until the operation has returned. Reinitialization clears the cancellation request.
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
     * Runs taint analysis and returns the legacy textual reports. Failed, timed-out, or
     * cancelled analyses throw instead of being mistaken for successful empty analyses.
     * Use runTaintAnalysisResult to inspect partial findings and structured diagnostics.
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

    /** Runs an analysis with a newly generated identifier and returns detached immutable data. */
    public TaintAnalysisResult runTaintAnalysisResult(Set<AndroidMethod> sources, Set<AndroidMethod> sinks) {
        return runTaintAnalysisResult(sources, sinks, UUID.randomUUID());
    }

    /**
     * Runs an analysis associated with the caller's identifier. Failed and incomplete results
     * retain any available findings. Invalid arguments and missing initialization remain programming
     * errors and throw; runtime analysis failures are represented by FAILED with diagnostics.
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
     * Returns the package name of the Android application.
     *
     * @return the package name of the Android application, or null if the Manifest file could not be processed
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
