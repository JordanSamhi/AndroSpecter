package com.jordansamhi.androspecter;

import soot.*;
import soot.util.Chain;

import java.util.Iterator;

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
     * @param sc The SootClass to be examined.
     */
    public abstract void examineClass(SootClass sc);

    /**
     * Examines the specified SootMethod within a SootClass.
     *
     * @param sc The SootClass containing the SootMethod.
     * @param sm The SootMethod to be examined.
     */
    public abstract void examineMethod(SootClass sc, SootMethod sm);

    /**
     * Examines the specified statement (Unit) within a SootMethod of a SootClass.
     *
     * @param sc The SootClass containing the SootMethod.
     * @param sm The SootMethod containing the statement (Unit).
     * @param u  The statement (Unit) to be examined.
     */
    public abstract void examineStatement(SootClass sc, SootMethod sm, Unit u);

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
            processClass(sc);
        }
    }

    /**
     * Processes a SootClass, examining it and its methods and statements as per the criteria.
     *
     * @param sc The SootClass to process.
     */
    private void processClass(SootClass sc) {
        if (examineClasses) {
            examineClass(sc);
        }
        Iterator<SootMethod> methodIterator = sc.getMethods().iterator();
        while (methodIterator.hasNext()) {
            SootMethod sm = methodIterator.next();
            processMethod(sc, sm);
        }
    }

    /**
     * Processes a SootMethod, examining it and its statements as per the criteria.
     *
     * @param sc The SootClass containing the SootMethod.
     * @param sm The SootMethod to process.
     */
    private void processMethod(SootClass sc, SootMethod sm) {
        if (examineMethods) {
            examineMethod(sc, sm);
        }
        if (sm.isConcrete()) {
            processStatements(sc, sm, sm.retrieveActiveBody());
        }
    }

    /**
     * Processes the statements of a method body as per the criteria.
     *
     * @param sc   The SootClass containing the SootMethod.
     * @param sm   The SootMethod containing the Body.
     * @param body The Body containing the statements to process.
     */
    private void processStatements(SootClass sc, SootMethod sm, Body body) {
        Chain<Unit> units = body.getUnits();
        Iterator<Unit> iterator = units.snapshotIterator();
        while (iterator.hasNext()) {
            Unit u = iterator.next();
            if (examineStatements) {
                examineStatement(sc, sm, u);
            }
        }
    }
}