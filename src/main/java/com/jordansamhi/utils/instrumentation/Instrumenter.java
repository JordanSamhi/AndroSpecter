package com.jordansamhi.utils.instrumentation;

import com.jordansamhi.utils.SootUtils;
import com.jordansamhi.utils.utils.Constants;
import soot.*;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.StringConstant;
import soot.util.Chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Singleton class for instrumenting SootMethods
 */
public class Instrumenter {

    private static Instrumenter instance;

    /**
     * Private constructor to prevent external instantiation.
     */
    private Instrumenter() {
    }

    /**
     * Returns the singleton instance of the class. If it doesn't exist, it creates one.
     *
     * @return the singleton instance of Instrumenter.
     */
    public static Instrumenter v() {
        if (instance == null) {
            instance = new Instrumenter();
        }
        return instance;
    }

    /**
     * Adds a log statement to all methods of application classes. This excludes system classes and libraries.
     * Returns a Transform object which encapsulates the added transformation for the "jtp" (Jimple Transformation Pack) phase.
     *
     * @param tagToLog the tag to be used in the log statement.
     * @return a Transform object containing the scene transformation for logging.
     */
    public Transform addLogToAllMethods(String tagToLog) {
        return new Transform("jtp.myLogger", new BodyTransformer() {
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                SootMethod sm = b.getMethod();
                addLogToMethod(sm, tagToLog, sm.getSignature());
            }
        });
    }

    /**
     * Adds a log statement to a SootMethod. The log is added right after all identity statements in the method.
     *
     * @param sm           SootMethod to which the log statement will be added.
     * @param tagToLog     the tag to be used in the log statement.
     * @param messageToLog the message to be logged.
     */
    public void addLogToMethod(SootMethod sm, String tagToLog, String messageToLog) {
        if (sm.isConcrete()) {
            Body b = sm.retrieveActiveBody();
            JimpleBody jb = (JimpleBody) b;
            Chain<Unit> units = b.getUnits();
            Unit entrypoint = jb.getFirstNonIdentityStmt();
            SootUtils su = new SootUtils();
            SootMethodRef logMethodRef = su.getMethodRef(Constants.ANDROID_UTIL_LOG, Constants.LOG_D);
            List<Unit> unitsToAdd = new ArrayList<>();
            List<Value> params = new ArrayList<>();
            Value tag = StringConstant.v(tagToLog);
            Value message = StringConstant.v(messageToLog);
            params.add(tag);
            params.add(message);
            Unit newUnit = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(logMethodRef, params));
            unitsToAdd.add(newUnit);
            units.insertBefore(unitsToAdd, entrypoint);
            b.validate();
        }
    }
}
