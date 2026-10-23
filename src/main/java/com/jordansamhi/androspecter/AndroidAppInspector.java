package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.analysis.AnalysisContext;
import com.jordansamhi.androspecter.inspection.Inspection;
import soot.*;

/**
 * Legacy visitor adapter. Prefer {@link Inspection} to register only the callbacks and filters needed.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
@Deprecated
public abstract class AndroidAppInspector {

    private boolean examineClasses = true;
    private boolean examineMethods = true;
    private boolean examineStatements = true;

    /**
     * Examines the specified SootClass.
     *
     * @param sc The SootClass to be examined.
     */
    public void examineClass(SootClass sc) {}

    /**
     * Examines the specified SootMethod within a SootClass.
     *
     * @param sc The SootClass containing the SootMethod.
     * @param sm The SootMethod to be examined.
     */
    public void examineMethod(SootClass sc, SootMethod sm) {}

    /**
     * Examines the specified statement (Unit) within a SootMethod of a SootClass.
     *
     * @param sc The SootClass containing the SootMethod.
     * @param sm The SootMethod containing the statement (Unit).
     * @param u  The statement (Unit) to be examined.
     */
    public void examineStatement(SootClass sc, SootMethod sm, Unit u) {}

    /**
     * Sets the criteria for examination.
     *
     * @param examineClasses    boolean to decide whether to examine classes.
     * @param examineMethods    boolean to decide whether to examine methods.
     * @param examineStatements boolean to decide whether to examine statements.
     */
    public void setExaminationCriteria(boolean examineClasses, boolean examineMethods, boolean examineStatements) {
        this.examineClasses = examineClasses;
        this.examineMethods = examineMethods;
        this.examineStatements = examineStatements;
    }

    /**
     * Starts the inspection process, examining classes, methods, and statements as per the criteria.
     */
    public void run() {
        try (AnalysisContext context = AnalysisContext.capture()) {
            runIn(context);
        }
    }

    /**
     * Uses an existing context, including its cancellation and stale-scene checks.
     *
     * @param context the current analysis context
     */
    public void runIn(AnalysisContext context) {
        Inspection.Builder inspection = Inspection.builder();
        if (examineClasses) inspection.onClass(this::examineClass);
        if (examineMethods) inspection.onMethod(method -> examineMethod(method.getDeclaringClass(), method));
        if (examineStatements) inspection.onStatement((method, unit) ->
                examineStatement(method.getDeclaringClass(), method, unit));
        inspection.build().run(context);
    }
}
