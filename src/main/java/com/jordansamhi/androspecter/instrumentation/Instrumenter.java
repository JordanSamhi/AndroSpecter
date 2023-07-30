package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.util.Chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Singleton class for instrumenting SootMethods
 *
 * @author Jordan Samhi
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
     * Adds a log statement to all methods of application classes. Returns a Transform object which encapsulates
     * the added transformation for the specified phase.
     *
     * @param tagToLog  the tag to be used in the log statement.
     * @param phaseName the name of the phase during which this method is invoked.
     * @return a Transform object containing the scene transformation for logging.
     */
    public Transform addLogToAllMethods(String tagToLog, String phaseName) {
        return new Transform(phaseName, new BodyTransformer() {
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                SootMethod sm = b.getMethod();
                addLogToMethod(sm, tagToLog, sm.getSignature());
            }
        });
    }


    /**
     * Inserts a log statement at a specified location within a set of units in the body of a method.
     *
     * @param units          The chain of units in the method where the log statement is to be inserted.
     * @param insertionPoint The specific unit in the method where the log statement is to be inserted.
     * @param tagToLog       The tag to be used in the log statement.
     * @param messageToLog   The message to be logged.
     * @param b              The body of the method where the log statement is to be inserted.
     */
    public void addLogStatement(Chain<Unit> units, Unit insertionPoint, String tagToLog, String messageToLog, Body b) {
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
        units.insertBefore(unitsToAdd, insertionPoint);
        b.validate();
    }

    /**
     * Modifies all method calls within a body to include a log statement that logs the signature of the calling and called method.
     * The log statement is inserted as the first instruction of each method invocation.
     *
     * @param tagToLog The tag to be used in the log statements.
     * @return A Transform object that represents the modified method body.
     */
    public Transform addLogToAllMethodCalls(String tagToLog, String phaseName) {
        return new Transform(phaseName, new BodyTransformer() {
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                Chain<Unit> units = b.getUnits();
                Map<Unit, String> insertionPointsToMessage = new HashMap<>();
                for (Unit u : units) {
                    Stmt stmt = (Stmt) u;
                    InvokeExpr ie;
                    if (stmt.containsInvokeExpr()) {
                        ie = stmt.getInvokeExpr();
                        String messageToLog = String.format("%s-->%s", b.getMethod().getSignature(), ie.getMethod().getSignature());
                        insertionPointsToMessage.put(u, messageToLog);
                    }
                }
                for (Map.Entry<Unit, String> e : insertionPointsToMessage.entrySet()) {
                    addLogStatement(units, e.getKey(), tagToLog, e.getValue(), b);
                }
            }
        });
    }

    /**
     * Inserts a log statement right after all identity statements in the given method.
     * The message logged is passed as a parameter.
     *
     * @param sm           The method where the log statement is to be inserted.
     * @param tagToLog     The tag to be used in the log statement.
     * @param messageToLog The message to be logged.
     */
    public void addLogToMethod(SootMethod sm, String tagToLog, String messageToLog) {
        if (sm.isConcrete()) {
            Body b = sm.retrieveActiveBody();
            JimpleBody jb = (JimpleBody) b;
            Chain<Unit> units = b.getUnits();
            Unit entrypoint = jb.getFirstNonIdentityStmt();
            addLogStatement(units, entrypoint, tagToLog, messageToLog, b);
        }
    }
}
