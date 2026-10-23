package com.jordansamhi.androspecter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soot.G;
import soot.IntType;
import soot.Modifier;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.VoidType;
import soot.jimple.IntConstant;
import soot.jimple.Jimple;
import soot.jimple.infoflow.FlowDroidLocalSplitter.SplittedLocal;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.toolkits.callgraph.CallGraph;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReusableSetupApplicationTest {
    @AfterEach
    void reset() {
        G.reset();
    }

    @Test
    void restoresOnlyOriginalsReferencedBySplitLocalsWithoutDuplicatingThem() {
        SootClass clazz = new SootClass("Example");
        Scene.v().addClass(clazz);
        SootMethod method = new SootMethod("method", List.of(), VoidType.v(), Modifier.STATIC);
        clazz.addMethod(method);
        var body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        var original = new JimpleLocal("value", IntType.v());
        var split = new SplittedLocal(original);
        var unrelated = new JimpleLocal("undeclared", IntType.v());
        body.getLocals().add(split);
        body.getUnits().add(Jimple.v().newAssignStmt(split, IntConstant.v(1)));
        body.getUnits().add(Jimple.v().newAssignStmt(unrelated, split));

        ReusableSetupApplication.restoreSplitOriginalLocals();
        ReusableSetupApplication.restoreSplitOriginalLocals();
        assertTrue(body.getLocals().contains(original));
        assertTrue(body.getLocals().contains(split));
        assertFalse(body.getLocals().contains(unrelated), "Do not silently repair unrelated caller errors");
        assertEquals(2, body.getLocals().size());
    }

    @Test
    void graphFailuresPropagateWhileTaintFailuresRemainStructuredInBothDirections() {
        for (var direction : InfoflowConfiguration.DataFlowDirection.values()) {
            for (boolean taint : List.of(false, true)) {
                G.reset();
                Scene.v().addClass(new SootClass("java.lang.Object", Modifier.PUBLIC));
                Scene.v().setCallGraph(new CallGraph());
                var configuration = new InfoflowAndroidConfiguration();
                configuration.setDataFlowDirection(direction);
                configuration.setTaintAnalysisEnabled(taint);
                configuration.setSootIntegrationMode(InfoflowConfiguration.SootIntegrationMode.UseExistingCallgraph);
                configuration.setCodeEliminationMode(InfoflowConfiguration.CodeEliminationMode.NoCodeElimination);
                var application = new ReusableSetupApplication(configuration);
                var failure = new IllegalStateException("Deliberate CFG construction failure");
                application.setIcfgFactory((algorithm, exceptions) -> { throw failure; });
                var engine = application.createInfoflowInternal(List.of());
                engine.setConfig(configuration);
                if (taint) {
                    assertDoesNotThrow(() -> engine.runAnalysis(null, null));
                    assertTrue(engine.getResults().getExceptions().stream()
                            .anyMatch(message -> message.contains(failure.getMessage())));
                } else {
                    assertSame(failure, assertThrows(IllegalStateException.class,
                            () -> engine.runAnalysis(null, null)));
                }
            }
        }
    }
}
