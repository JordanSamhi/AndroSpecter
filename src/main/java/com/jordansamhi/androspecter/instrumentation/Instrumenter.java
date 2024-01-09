package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.options.Options;
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

    private final SootUtils su;

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
     * @param after          Whether inserting the log statement after the insertionPoint
     */
    public void addLogStatement(Chain<Unit> units, Unit insertionPoint, String tagToLog, String messageToLog, Body b, boolean after) {
        SootMethodRef logMethodRef = this.su.getMethodRef(Constants.ANDROID_UTIL_LOG, Constants.LOG_D);
        List<Unit> unitsToAdd = new ArrayList<>();
        List<Value> params = new ArrayList<>();
        Value tag = StringConstant.v(tagToLog);
        Value message = StringConstant.v(messageToLog);
        params.add(tag);
        params.add(message);
        Unit newUnit = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(logMethodRef, params));
        unitsToAdd.add(newUnit);
        if (after) {
            units.insertAfter(unitsToAdd, insertionPoint);
        } else {
            units.insertBefore(unitsToAdd, insertionPoint);
        }

        // Not sure why but some methods with return type set as "void"
        // do not have a return statement in Jimple, probably a bug in Soot
        this.patchReturnStatement(b.getMethod().getReturnType(), units);

        b.validate();
    }

    /**
     * Adds a return statement to a list of units based on the return type.
     *
     * @param returnType The return type of the method.
     * @param units      The list of units to which a return statement is added.
     */
    private void patchReturnStatement(Type returnType, Chain<Unit> units) {
        boolean hasRet = false;
        Unit newStmt = null;
        Type voidType = RefType.v(Constants.JAVA_LANG_VOID);

        if (returnType instanceof VoidType) {
            hasRet = units.stream().anyMatch(u -> u instanceof ReturnVoidStmt);
            if (!hasRet) {
                newStmt = Jimple.v().newReturnVoidStmt();
            }
        } else if (returnType instanceof RefType && returnType.equals(voidType)) {
            hasRet = units.stream().anyMatch(u -> u instanceof ReturnStmt);
            if (!hasRet) {
                newStmt = Jimple.v().newReturnStmt(NullConstant.v());
            }
        }

        if (newStmt != null) {
            List<Unit> toAdd = new ArrayList<>();
            toAdd.add(newStmt);
            units.insertAfter(toAdd, units.getLast());
        }
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
        if (sm != null) {
            if (sm.isConcrete()) {
                Body b = sm.retrieveActiveBody();
                JimpleBody jb = (JimpleBody) b;
                Chain<Unit> units = b.getUnits();
                Unit entrypoint = jb.getFirstNonIdentityStmt();
                addLogStatement(units, entrypoint, tagToLog, messageToLog, b, false);
            }
        }
    }

    /**
     * Adds a log statement to all methods within the scope of the application classes.
     *
     * @param tagToLog The logging tag that will be used in the added log statements.
     */
    public void logAllMethods(String tagToLog) {
        addTransformation("jtp.methodsLogger", b -> {
            SootMethod sm = b.getMethod();
            addLogToMethod(sm, tagToLog, String.format("METHOD=%s", sm.getSignature()));
        });
    }

    /**
     * Adds a transformation to log all classes that are processed. This method specifically targets
     * the constructors (both instance and static) of the classes and injects logging instructions into them.
     * The logging is tagged with a specified tag and includes the class name.
     *
     * @param tagToLog The tag to be used in the logging statements. This helps in categorizing or filtering logs.
     */

    public void logAllClasses(String tagToLog) {
        addTransformation("jtp.classesLogger", b -> {
            SootMethod sm = b.getMethod();
            String methodName = sm.getName();
            if (methodName.equals(Constants.INIT) || methodName.equals(Constants.CLINIT)) {
                addLogToMethod(sm, tagToLog, String.format("CLASS=%s", sm.getDeclaringClass().getName()));
            }
        });
    }

    /**
     * Modifies all method calls within a body to include a log statement that logs the signature of the calling and called method.
     * The log statement is inserted as the first instruction of each method invocation.
     * Example of log added to method a() that calls method b(): a()--&gt;b()
     *
     * @param tagToLog The tag to be used in the log statements.
     */
    public void logAllMethodCalls(String tagToLog) {
        addTransformation("jtp.methodCallsLogger", b -> {
            Chain<Unit> units = b.getUnits();
            Map<Unit, String> insertionPointsToMessage = new HashMap<>();
            for (Unit u : units) {
                Stmt stmt = (Stmt) u;
                InvokeExpr ie;
                if (stmt.containsInvokeExpr()) {
                    ie = stmt.getInvokeExpr();
                    String messageToLog = String.format("CALL=%s-->%s", b.getMethod().getSignature(), ie.getMethod().getSignature());
                    insertionPointsToMessage.put(u, messageToLog);
                }
            }
            for (Map.Entry<Unit, String> e : insertionPointsToMessage.entrySet()) {
                addLogStatement(units, e.getKey(), tagToLog, e.getValue(), b, false);
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
     */
    private void logAndroidComponent(String tagToLog, String phaseName, String componentType,
                                     String methodSignature) {
        addTransformation(phaseName, b -> {
            SootMethod sm = b.getMethod();
            SootClass sc = sm.getDeclaringClass();
            String actualComponentType = su.getComponentType(sc);
            if (actualComponentType.equals(componentType) && sm.getSubSignature().equals(methodSignature)) {
                addLogToMethod(sm, tagToLog, String.format("%s=%s", componentType.toUpperCase(), sc.getName()));
            }
        });
    }

    /**
     * Registers a log statement to be inserted into Android Activities' {@code onCreate} methods.
     *
     * @param tagToLog Logging tag.
     */
    public void logActivities(String tagToLog) {
        logAndroidComponent(tagToLog, "jtp.activitiesLogger", Constants.ACTIVITY, Constants.ONCREATE_ACTIVITY);
    }

    /**
     * Registers a log statement to be inserted into Android Services' {@code onCreate} methods.
     *
     * @param tagToLog Logging tag.
     */
    public void logServices(String tagToLog) {
        logAndroidComponent(tagToLog, "jtp.servicesLogger", Constants.SERVICE, Constants.ONCREATE_SERVICE);
    }

    /**
     * Logs execution of Broadcast Receivers within a given phase.
     *
     * @param tagToLog The tag to be used in the log statement.
     */
    public void logBroadcastReceivers(String tagToLog) {
        logAndroidComponent(tagToLog, "jtp.broadcastReceiversLogger", Constants.BROADCAST_RECEIVER, Constants.ONRECEIVE);
    }

    /**
     * Logs execution of Content Providers within a given phase.
     *
     * @param tagToLog The tag to be used in the log statement.
     */
    public void logContentProviders(String tagToLog) {
        logAndroidComponent(tagToLog, "jtp.contentProvidersLogger", Constants.CONTENT_PROVIDER, Constants.ONCREATE_CONTENT_PROVIDER);
    }

    /**
     * Logs all executable statements in the code.
     *
     * @param tagToLog The tag to be used in the log statement.
     */
    public void logAllStatements(String tagToLog) {
        addTransformation("jtp.statementsLogger", b -> {
            Chain<Unit> units = b.getUnits();
            Map<Unit, String> insertionPointsToMessage = new HashMap<>();
            int cnt = 0;
            for (Unit u : units) {
                cnt++;
                Stmt stmt = (Stmt) u;
                if (!(stmt instanceof IdentityStmt)) {
                    String messageToLog = String.format("STATEMENT=%s|%s|%d", b.getMethod(), stmt, cnt);
                    insertionPointsToMessage.put(u, messageToLog);
                }
            }
            for (Map.Entry<Unit, String> e : insertionPointsToMessage.entrySet()) {
                addLogStatement(units, e.getKey(), tagToLog, e.getValue(), b, true);
            }
        });
    }

    /**
     * Executes the Soot packs to perform code instrumentation.
     */
    public void instrument() {
        PackManager.v().runPacks();
    }

    /**
     * Exports the modified APK with default settings specified
     * when loading the app into soot with set_output_format and set_output_dir
     */
    public void exportNewApk() {
        //TODO: check file existence
        PackManager.v().writeOutput();
    }

    /**
     * Exports the modified APK to a specified path.
     *
     * @param path The directory path where the APK will be exported.
     */
    public void exportNewApk(String path) {
        //TODO: check file existence
        Options.v().set_output_format(Options.output_format_dex);
        Options.v().set_output_dir(path);
        PackManager.v().writeOutput();
    }
}