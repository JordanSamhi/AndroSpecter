package com.jordansamhi.utils;

import com.jordansamhi.utils.config.SootConfig;
import soot.G;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.config.IInfoflowConfig;

/**
 * A utility class for initializing and running Flowdroid analyses on Android apps.
 */
public class FlowdroidUtils {

    private ProcessManifest pm;
    private final String apkPath;

    public FlowdroidUtils(String apkPath) {
        this.apkPath = apkPath;
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
        return sa;
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