package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.config.SootConfig;
import com.jordansamhi.androspecter.printers.Writer;
import soot.G;
import soot.SootMethod;
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

import java.util.*;

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

    private ProcessManifest pm;
    private final String apkPath;
    private SetupApplication sa;

    public FlowdroidUtils(String apkPath) {
        this.apkPath = apkPath;
        this.sa = null;
        try {
            pm = new ProcessManifest(this.apkPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Initializes a Flowdroid analysis on an Android app.
     *
     * @param platformPath        The path to the directory containing the Android platform.
     * @param config              The configuration to be used for the analysis, or null to use default configuration.
     * @param CallGraphAlgo       The algorithm to be used for constructing the call graph (CHA, RTA, VTA, or SPARK).
     * @param useExistingInstance Whether to use an existing Soot instance.
     * @return The SetupApplication object representing the analysis.
     */
    public SetupApplication initializeFlowdroid(String platformPath, IInfoflowConfig config, String CallGraphAlgo, boolean useExistingInstance) {
        G.reset();
        InfoflowAndroidConfiguration ifac = new InfoflowAndroidConfiguration();
        ifac.getAnalysisFileConfig().setAndroidPlatformDir(platformPath);
        ifac.getAnalysisFileConfig().setTargetAPKFile(this.apkPath);
        ifac.setMergeDexFiles(true);
        ifac.setCodeEliminationMode(InfoflowConfiguration.CodeEliminationMode.NoCodeElimination);
        SetupApplication sa = new SetupApplication(ifac);
        if (config == null) {
            sa.setSootConfig(new SootConfig());
        } else {
            sa.setSootConfig(config);
        }
        InfoflowConfiguration.CallgraphAlgorithm cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.CHA;
        switch (CallGraphAlgo) {
            case "RTA":
                cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.RTA;
                break;
            case "VTA":
                cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.VTA;
                break;
            case "SPARK":
                cgAlgo = InfoflowConfiguration.CallgraphAlgorithm.SPARK;
                break;
        }
        sa.getConfig().setCallgraphAlgorithm(cgAlgo);
        sa.constructCallgraph();
        if (useExistingInstance) {
            sa.getConfig().setSootIntegrationMode(InfoflowAndroidConfiguration.SootIntegrationMode.UseExistingInstance);
        }
        this.sa = sa;
        return sa;
    }

    /**
     * Runs the taint analysis on the provided set of sources and sinks.
     *
     * The method throws a NullPointerException if the SetupApplication has not been initialized before calling this method.
     * It then runs the Infoflow analysis and stores the results in a set of strings where each string represents a leak path
     * from a source to a sink in the format:
     * "Found leak:
     *   - From [source]
     *     - Detailed path:
     *        [statement] => in method: [method]
     *   - To [sink]"
     *
     * If no flow is found, a warning message is printed.
     *
     * @param sources the set of sources to consider in the taint analysis
     * @param sinks   the set of sinks to consider in the taint analysis
     * @return a set of strings, each string describing a path from a source to a sink
     * @throws NullPointerException if the SetupApplication has not been initialized
     */
    public Set<String> runTaintAnalysis(Set<AndroidMethod> sources, Set<AndroidMethod> sinks) {
        if (this.sa == null) {
            throw new NullPointerException("The SetupApplication has not been initialized.\n" +
                    "First call the initializeFlowdroid method.");
        }
        InfoflowResults results = null;
        try {
            results = sa.runInfoflow(sources, sinks);
        } catch (Exception e) {

        }
        InfoflowCFG icfg = new InfoflowCFG();
        List<SootMethod> sourceList = null;
        Set<String> resultList = new HashSet<>();
        if (results != null) {
            if (results.getResults() != null && !results.getResults().isEmpty()) {
                for (ResultSinkInfo sink : results.getResults().keySet()) {
                    for (ResultSourceInfo source : results.getResults().get(sink)) {
                        List<Stmt> path = Arrays.asList(source.getPath());
                        if (path != null && !path.isEmpty()) {
                            StringBuilder resultBuilder = new StringBuilder();
                            resultBuilder.append("Found leak: \n");
                            resultBuilder.append("  - From ").append(source).append("\n");
                            resultBuilder.append("    - Detailed path:\n");
                            for (Stmt s : path) {
                                resultBuilder.append("       ").append(s).append(" => in method: ").append(icfg.getMethodOf(s)).append("\n");
                            }
                            resultBuilder.append("  - To ").append(sink).append("\n");
                            resultList.add(resultBuilder.toString());
                        }
                    }
                }
            } else {
                Writer.v().pwarning("No Flow found.");
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
        if (this.pm != null) {
            return this.pm.getPackageName();
        }
        return null;
    }
}