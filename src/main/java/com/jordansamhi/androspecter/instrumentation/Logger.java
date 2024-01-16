package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;
import soot.options.Options;
import soot.util.Chain;

import java.util.*;
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
 * Singleton class for instrumenting SootMethods to add log statements
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class Logger {

    private final SootUtils su;

    private static Logger instance;

    /**
     * Private constructor to prevent external instantiation.
     */
    private Logger() {
        su = new SootUtils();
    }

    /**
     * Returns the singleton instance of the class. If it doesn't exist, it creates one.
     *
     * @return the singleton instance of Instrumenter.
     */
    public static Logger v() {
        if (instance == null) {
            instance = new Logger();
            LogCheckerClass.v().generateClass();
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
     * Inserts a log statement into a chain of units at a specified point.
     * This method is used to dynamically add logging statements to the bytecode during the transformation process.
     * It handles the insertion of a log statement either before or after a given unit (instruction) in a method's body.
     * The method also addresses an anomaly where some methods with a void return type may not have a return statement in Jimple (possibly a Soot bug).
     *
     * @param units          The chain of units (instructions) in a method's body where the log statement is to be inserted.
     * @param insertionPoint The specific unit (instruction) after or before which the log statement is to be inserted.
     * @param tagToLog       The tag associated with the log statement. This is used for categorizing the log entries.
     * @param messageToLog   The message to be logged. This typically contains information about the statement being logged.
     * @param b              The body of the method where the logging is to be inserted. This is used for validation.
     * @param after          A boolean indicating whether the log statement should be inserted after (true) or before (false) the insertion point.
     */
    public void addLogStatement(Chain<Unit> units, Unit insertionPoint, String tagToLog, String messageToLog, Body b, boolean after) {
        // Not sure why but some methods with return type set as "void"
        // do not have a return statement in Jimple, probably a bug in Soot
        this.patchReturnStatement(b.getMethod().getReturnType(), units);

        SootMethodRef logMethodRef = this.su.getMethodRef(Constants.LOG_CHECKER_CLASS, Constants.SUB_SIG_LOG);
        List<Unit> unitsToAdd = new ArrayList<>();
        List<Value> params = new ArrayList<>();
        Value tag = StringConstant.v(tagToLog);
        Value message = StringConstant.v(messageToLog);
        params.add(message);
        params.add(tag);
        Unit newUnit = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(logMethodRef, params));

        unitsToAdd.add(newUnit);

        if (after) {
            units.insertAfter(unitsToAdd, insertionPoint);
        } else {
            units.insertBefore(unitsToAdd, insertionPoint);
        }

        b.validate();
    }

    /**
     * Retrieves the next {@code Unit} in a {@code Chain} following a specified insertion point.
     * This method iterates through the {@code Chain} of {@code Unit}s and returns the {@code Unit}
     * that immediately follows the specified {@code insertionPoint}. If the {@code insertionPoint}
     * is not found, or if it is the last element in the {@code Chain}, the method returns {@code null}.
     *
     * @param units          the {@code Chain<Unit>} to be searched.
     * @param insertionPoint the {@code Unit} that serves as the reference point for finding the next {@code Unit}.
     * @return the next {@code Unit} following the {@code insertionPoint}, or {@code null} if the insertion point
     * is not found or is the last element in the {@code Chain}.
     */
    private Unit getNextUnit(Chain<Unit> units, Unit insertionPoint) {
        boolean foundInsertionPoint = false;
        for (Unit unit : units) {
            if (foundInsertionPoint) {
                return unit;
            }
            if (unit.equals(insertionPoint)) {
                foundInsertionPoint = true;
            }
        }
        return null;
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
            if (this.isLogCheckerClass(sm)) {
                return;
            }
            addLogToMethod(sm, tagToLog, String.format("METHOD=%s", sm.getSignature()));
        });
    }

    private boolean isLogCheckerClass(SootMethod sm) {
        String className = sm.getDeclaringClass().getName();
        return className.equals(Constants.LOG_CHECKER_CLASS);
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
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
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
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
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
     * @param tagToLog      Tag to log in the log statement.
     * @param phaseName     Phase during which this method is invoked.
     * @param componentType Type of the Android component (Activity, Service, etc.).
     */
    private void logAndroidComponent(String tagToLog, String phaseName, String componentType, Set<String> methods) {
        addTransformation(phaseName, b -> {
            SootMethod sm = b.getMethod();
            SootClass sc = sm.getDeclaringClass();
            String actualComponentType = su.getComponentType(sc);
            if (actualComponentType.equals(componentType) && methods.contains(sm.getSubSignature())) {
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
        Set<String> methods = new HashSet<>();
        methods.add(Constants.ONCREATE_ACTIVITY);
        methods.add(Constants.ONCREATE1_ACTIVITY);
        methods.add(Constants.ONSTART_ACTIVITY);
        methods.add(Constants.ONRESTART_ACTIVITY);
        methods.add(Constants.ONSTATENOTSAVED_ACTIVITY);
        methods.add(Constants.ONRESUME_ACTIVITY);
        methods.add(Constants.ONPAUSE_ACTIVITY);
        methods.add(Constants.ONSTOP_ACTIVITY);
        methods.add(Constants.ONDESTROY_ACTIVITY);
        logAndroidComponent(tagToLog, "jtp.activitiesLogger", Constants.ACTIVITY, methods);
    }

    /**
     * Registers a log statement to be inserted into Android Services' {@code onCreate} methods.
     *
     * @param tagToLog Logging tag.
     */
    public void logServices(String tagToLog) {
        Set<String> methods = new HashSet<>();
        methods.add(Constants.ONCREATE_SERVICE);
        methods.add(Constants.ONSTART_SERVICE);
        methods.add(Constants.ONSTARTCOMMAND_SERVICE);
        methods.add(Constants.ONDESTROY_SERVICE);
        methods.add(Constants.ONBIND_SERVICE);
        methods.add(Constants.ONUNBIND_SERVICE);
        methods.add(Constants.ONREBIND_SERVICE);
        logAndroidComponent(tagToLog, "jtp.servicesLogger", Constants.SERVICE, methods);
    }

    /**
     * Logs execution of Broadcast Receivers within a given phase.
     *
     * @param tagToLog The tag to be used in the log statement.
     */
    public void logBroadcastReceivers(String tagToLog) {
        Set<String> methods = new HashSet<>();
        methods.add(Constants.ONRECEIVE);
        logAndroidComponent(tagToLog, "jtp.broadcastReceiversLogger", Constants.BROADCAST_RECEIVER, methods);
    }

    /**
     * Logs execution of Content Providers within a given phase.
     *
     * @param tagToLog The tag to be used in the log statement.
     */
    public void logContentProviders(String tagToLog) {
        Set<String> methods = new HashSet<>();
        methods.add(Constants.ONCREATE_CONTENT_PROVIDER);
        logAndroidComponent(tagToLog, "jtp.contentProvidersLogger", Constants.CONTENT_PROVIDER, methods);
    }

    /**
     * Logs all executable statements in a given code body except for identity, return, and monitor statements.
     * It iterates through each unit in the code body and generates log statements for each executable unit.
     * The log statements are annotated with a unique identifier for the statement, the method containing it, and a sequence number.
     * This method is especially useful for tracking the execution flow and identifying the occurrence of specific statements.
     *
     * @param tagToLog The tag to be used in the log statement. This tag is used to categorize the log entries.
     */
    public void logAllStatements(String tagToLog) {
        addTransformation("jtp.statementsLogger", b -> {
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
            Chain<Unit> units = b.getUnits();
            Map<Unit, String> insertionPointsToMessage = new HashMap<>();
            int cnt = 0;
            for (Unit u : units) {
                cnt++;
                Stmt stmt = (Stmt) u;
                if (stmt instanceof IdentityStmt) {
                    continue;
                }
                if (stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
                    continue;
                }
                if (stmt instanceof MonitorStmt) {
                    continue;
                }
                String messageToLog = String.format("STATEMENT=%s|%s|%d", b.getMethod(), stmt, cnt);
                insertionPointsToMessage.put(u, messageToLog);
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