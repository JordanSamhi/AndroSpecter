package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.printers.Writer;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.util.Chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class Instrumenter {

    private SootUtils su;

    private static Instrumenter instance;

    /**
     * Private constructor to prevent external instantiation.
     */
    private Instrumenter() {
        su = new SootUtils();
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
     * Adds a transformation to the Jimple Transformation Pack (jtp) for a specific phase.
     *
     * @param phaseName The name of the phase during which this transformation should be applied.
     * @param logic     The logic to execute during the transformation, encapsulated as a Consumer of Body.
     */
    private void addTransformation(String phaseName, Consumer<Body> logic) {
        PackManager.v().getPack("jtp").add(new Transform(phaseName, new BodyTransformer() {
            @Override
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                logic.accept(b);
            }
        }));
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
        SootMethodRef logMethodRef = this.su.getMethodRef(Constants.ANDROID_UTIL_LOG, Constants.LOG_D);
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

    /**
     * Adds a log statement to all methods within the scope of the application classes.
     *
     * @param tagToLog  The logging tag that will be used in the added log statements.
     * @param phaseName The name of the Soot phase during which this transformation is to be applied.
     */
    public void logAllMethods(String tagToLog, String phaseName) {
        addTransformation(phaseName, b -> {
            SootMethod sm = b.getMethod();
            addLogToMethod(sm, tagToLog, sm.getSignature());
        });
    }

    /**
     * Modifies all method calls within a body to include a log statement that logs the signature of the calling and called method.
     * The log statement is inserted as the first instruction of each method invocation.
     * Example of log added to method a() that calls method b(): a()-->b()
     *
     * @param tagToLog  The tag to be used in the log statements.
     * @param phaseName The phase during which this method is invoked.
     */
    public void logAllMethodCalls(String tagToLog, String phaseName) {
        addTransformation(phaseName, b -> {
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
        });
    }

    /**
     * Internal method to log Android components based on given parameters.
     *
     * @param tagToLog        Tag to log in the log statement.
     * @param phaseName       Phase during which this method is invoked.
     * @param componentType   Type of the Android component (Activity, Service, etc.).
     * @param methodSignature Signature of the method in the component.
     * @param logMessage      Message to log when conditions are met.
     */
    private void logAndroidComponent(String tagToLog, String phaseName, String componentType,
                                     String methodSignature, String logMessage) {
        addTransformation(phaseName, b -> {
            SootMethod sm = b.getMethod();
            SootClass sc = sm.getDeclaringClass();
            String actualComponentType = su.getComponentType(sc);
            if (actualComponentType.equals(componentType) && sm.getSubSignature().equals(methodSignature)) {
                addLogToMethod(sm, tagToLog, logMessage);
            }
        });
    }

    /**
     * Registers a log statement to be inserted into Android Activities' {@code onCreate} methods.
     *
     * @param tagToLog  Logging tag.
     * @param phaseName Soot phase name for the transformation.
     */
    public void logActivities(String tagToLog, String phaseName) {
        logAndroidComponent(tagToLog, phaseName, Constants.ACTIVITY, Constants.ONCREATE_ACTIVITY, "ACTIVITY_EXECUTED");
    }

    /**
     * Registers a log statement to be inserted into Android Services' {@code onCreate} methods.
     *
     * @param tagToLog  Logging tag.
     * @param phaseName Soot phase name for the transformation.
     */
    public void logServices(String tagToLog, String phaseName) {
        logAndroidComponent(tagToLog, phaseName, Constants.SERVICE, Constants.ONCREATE_SERVICE, "SERVICE_EXECUTED");
    }

    /**
     * Logs execution of Broadcast Receivers within a given phase.
     *
     * @param tagToLog  The tag to be used in the log statement.
     * @param phaseName The phase during which this method is invoked.
     */
    public void logBroadcastReceivers(String tagToLog, String phaseName) {
        logAndroidComponent(tagToLog, phaseName, Constants.BROADCAST_RECEIVER, Constants.ONRECEIVE, "BROADCAST_RECEIVER_EXECUTED");
    }

    /**
     * Logs execution of Content Providers within a given phase.
     *
     * @param tagToLog  The tag to be used in the log statement.
     * @param phaseName The phase during which this method is invoked.
     */
    public void logContentProviders(String tagToLog, String phaseName) {
        logAndroidComponent(tagToLog, phaseName, Constants.CONTENT_PROVIDER, Constants.ONCREATE_CONTENT_PROVIDER, "CONTENT_PROVIDER_EXECUTED");
    }

    /**
     * Executes the Soot packs to perform code instrumentation.
     */
    public void instrument() {
        Writer.v().pinfo("Instrumenting...");
        PackManager.v().runPacks();
        Writer.v().psuccess("Instrumentation done.");
    }
}