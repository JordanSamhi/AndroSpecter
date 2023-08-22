package com.jordansamhi.androspecter;

import soot.*;
import soot.util.Chain;

/**
 * Abstract class to inspect Android classes, methods, and statements.
 * Implement the abstract methods to define custom actions for inspection.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public abstract class AndroidAppInspector {

    private boolean examineClasses = true;
    private boolean examineMethods = true;
    private boolean examineStatements = true;

    /**
     * Examines the specified SootClass.
     *
     * @param sc the SootClass to be examined.
     */
    public abstract void examineClass(SootClass sc);

    /**
     * Examines the specified SootMethod.
     *
     * @param sm the SootMethod to be examined.
     */
    public abstract void examineMethod(SootMethod sm);

    /**
     * Examines the specified statement (Unit).
     *
     * @param u the statement (Unit) to be examined.
     */
    public abstract void examineStatement(Unit u);

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
        for (SootClass sc : Scene.v().getClasses()) {
            if (!examineClasses || !shouldExamineClass(sc)) continue;
            this.examineClass(sc);
            for (SootMethod sm : sc.getMethods()) {
                if (!examineMethods || !shouldExamineMethod(sm)) continue;
                this.examineMethod(sm);
                if (sm.isConcrete()) {
                    Body b = sm.retrieveActiveBody();
                    Chain<Unit> units = b.getUnits();
                    for (Unit u : units) {
                        if (!examineStatements || !shouldExamineStatement(u)) continue;
                        this.examineStatement(u);
                    }
                }
            }
        }
    }

    /**
     * Optional filter to decide whether to examine a class.
     *
     * @param sc the SootClass to check.
     * @return true if the class should be examined, false otherwise.
     */
    protected boolean shouldExamineClass(SootClass sc) {
        return true;
    }

    /**
     * Optional filter to decide whether to examine a method.
     *
     * @param sm the SootMethod to check.
     * @return true if the method should be examined, false otherwise.
     */
    protected boolean shouldExamineMethod(SootMethod sm) {
        return true;
    }

    /**
     * Optional filter to decide whether to examine a statement.
     *
     * @param u the statement (Unit) to check.
     * @return true if the statement should be examined, false otherwise.
     */
    protected boolean shouldExamineStatement(Unit u) {
        return true;
    }
}