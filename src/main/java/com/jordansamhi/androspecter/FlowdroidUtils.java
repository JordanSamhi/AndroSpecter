package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.config.SootConfig;
import com.jordansamhi.androspecter.printers.Writer;
import soot.G;
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
import soot.jimple.infoflow.solver.cfg.InfoflowCFG;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
    private SetupApplication sa;

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
        this.sa = null;
        Objects.requireNonNull(platformPath, "platformPath");
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
        ifac.setMergeDexFiles(true);
        ifac.setCodeEliminationMode(InfoflowConfiguration.CodeEliminationMode.NoCodeElimination);
        ifac.getPathConfiguration().setPathReconstructionMode(InfoflowConfiguration.PathReconstructionMode.Precise);
        SetupApplication sa = createSetupApplication(ifac);
        if (config == null) {
            sa.setSootConfig(new SootConfig());
        } else {
            sa.setSootConfig(config);
        }
        sa.getConfig().setCallgraphAlgorithm(cgAlgo);
        sa.constructCallgraph();
        ifac.setSootIntegrationMode(InfoflowAndroidConfiguration.SootIntegrationMode.UseExistingInstance);
        this.sa = sa;
        return sa;
    }

    SetupApplication createSetupApplication(InfoflowAndroidConfiguration configuration) {
        return new SetupApplication(configuration);
    }

    /**
     * Runs the taint analysis on the provided set of sources and sinks.
     * <p>
     * The method requires an initialized SetupApplication. Failed or incomplete analyses throw
     * an IllegalStateException instead of being reported as an absence of flows.
     * It then runs the Infoflow analysis and stores the results in a set of strings where each string represents a leak path
     * from a source to a sink in the format:
     * "Found leak:
     * - From [source]
     * - Detailed path:
     * [statement] =&gt; in method: [method]
     * - To [sink]"
     * <p>
     * If no flow is found, a warning message is printed.
     *
     * @param sources the set of sources to consider in the taint analysis
     * @param sinks   the set of sinks to consider in the taint analysis
     * @return a set of strings, each string describing a path from a source to a sink
     * @throws IllegalStateException if setup is missing or the analysis fails or terminates early
     */
    public Set<String> runTaintAnalysis(Set<AndroidMethod> sources, Set<AndroidMethod> sinks) {
        if (this.sa == null) {
            throw new IllegalStateException("The SetupApplication has not been initialized.\n" +
                    "First call the initializeFlowdroid method.");
        }
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(sinks, "sinks");
        InfoflowResults results;
        try {
            results = this.sa.runInfoflow(sources, sinks);
        } catch (Exception e) {
            throw new IllegalStateException("FlowDroid taint analysis failed for " + this.apkPath, e);
        }
        if (results == null) {
            throw new IllegalStateException("FlowDroid returned no analysis result for " + this.apkPath);
        }
        if (results.getExceptions() != null && !results.getExceptions().isEmpty()) {
            throw new IllegalStateException("FlowDroid reported analysis errors: " + results.getExceptions());
        }
        if (results.getTerminationState() != InfoflowResults.TERMINATION_SUCCESS) {
            throw new IllegalStateException("FlowDroid analysis did not complete; termination state: "
                    + results.getTerminationState());
        }
        Set<String> resultList = new HashSet<>();
        if (results.isEmpty()) {
            Writer.v().pwarning("No Flow found.");
            return resultList;
        }
        InfoflowCFG icfg = null;
        for (ResultSinkInfo sink : results.getResults().keySet()) {
            for (ResultSourceInfo source : results.getResults().get(sink)) {
                StringBuilder resultBuilder = new StringBuilder("Found leak: \n");
                resultBuilder.append("  - From ").append(source).append("\n");
                Stmt[] path = source.getPath();
                if (path == null || path.length == 0) {
                    resultBuilder.append("    - Detailed path unavailable.\n");
                } else {
                    if (icfg == null) {
                        icfg = new InfoflowCFG();
                    }
                    resultBuilder.append("    - Detailed path:\n");
                    for (Stmt statement : path) {
                        resultBuilder.append("       ").append(statement).append(" => in method: ")
                                .append(icfg.getMethodOf(statement)).append("\n");
                    }
                }
                resultBuilder.append("  - To ").append(sink).append("\n");
                resultList.add(resultBuilder.toString());
            }
        }
        return resultList;
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
