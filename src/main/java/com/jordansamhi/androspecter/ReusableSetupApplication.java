package com.jordansamhi.androspecter;

import soot.Body;
import soot.Local;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.ValueBox;
import soot.jimple.infoflow.AbstractInfoflow;
import soot.jimple.infoflow.FlowDroidLocalSplitter.SplittedLocal;
import soot.jimple.infoflow.InfoflowConfiguration.DataFlowDirection;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;

import java.io.File;
import java.util.Collection;
import java.util.Iterator;

/**
 * Internal FlowDroid 2.15.1 adapter that preserves valid local declarations when an analysis unsplits bodies.
 * It adds missing original locals referenced by FlowDroid split locals before delegating to upstream cleanup;
 * it does not repair arbitrary caller-created invalid bodies or make scenes safe for concurrent use.
 */
final class ReusableSetupApplication extends SetupApplication {
    ReusableSetupApplication(InfoflowAndroidConfiguration configuration) {
        super(configuration);
    }

    /**
     * Creates the direction-specific in-place backend for the current mutable configuration and scene.
     * The engine restores original local declarations before upstream unsplitting. Graph-only operation
     * propagates backend exceptions; taint operation retains them in FlowDroid results for detachment.
     *
     * @param lifecycleMethods lifecycle method collection supplied by FlowDroid; retained by its engine
     * @return new live engine tied to this application's current Soot scene and configuration
     * @throws RuntimeException if backend engine construction fails
     */
    @Override
    protected IInPlaceInfoflow createInfoflowInternal(Collection<SootMethod> lifecycleMethods) {
        File android = config.getAnalysisFileConfig().getAndroidPlatformDir();
        IInPlaceInfoflow engine;
        if (config.getDataFlowDirection() == DataFlowDirection.Backwards) {
            engine = new InPlaceBackwardsInfoflow(android, forceAndroidJar, cfgFactory, lifecycleMethods) {
                /** Restores declarations of referenced original locals before upstream backward-analysis cleanup. */
                @Override
                protected void unsplitAllBodies() {
                    restoreSplitOriginalLocals();
                    super.unsplitAllBodies();
                }
            };
        } else {
            engine = new InPlaceInfoflow(android, forceAndroidJar, cfgFactory, lifecycleMethods) {
                /** Restores declarations of referenced original locals before upstream forward-analysis cleanup. */
                @Override
                protected void unsplitAllBodies() {
                    restoreSplitOriginalLocals();
                    super.unsplitAllBodies();
                }
            };
        }
        // Graph-only callers have no structured taint result in which to inspect errors.
        // Keep taint errors in InfoflowResults so partial findings remain available.
        ((AbstractInfoflow) engine).setThrowExceptions(!config.isTaintAnalysisEnabled());
        return engine;
    }

    // Upstream unsplitAllBodies rewrites SplittedLocal references and removes split
    // locals, but does not restore originals removed by UnusedLocalEliminator.
    // Register only originals referenced by split locals, leaving other invalid
    // caller-created bodies untouched. Revisit when upgrading FlowDroid.
    static void restoreSplitOriginalLocals() {
        for (SootClass clazz : Scene.v().getClasses()) {
            for (SootMethod method : clazz.getMethods()) {
                if (!method.hasActiveBody()) {
                    continue;
                }
                Body body = method.getActiveBody();
                Iterator<ValueBox> boxes = body.getUseAndDefBoxesIterator();
                while (boxes.hasNext()) {
                    if (boxes.next().getValue() instanceof SplittedLocal split) {
                        Local original = split.getOriginalLocal();
                        if (!body.getLocals().contains(original)) {
                            body.getLocals().add(original);
                        }
                    }
                }
            }
        }
    }
}
