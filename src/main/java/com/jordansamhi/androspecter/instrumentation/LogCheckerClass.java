package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.List;

/**
 * LogCheckerClass is a singleton class responsible for managing log operations.
 * It uses the Soot framework to manipulate and analyze Java bytecode.
 * It provides functionality to initialize a logging class, add logs, check if a log has already been made,
 * and log messages conditionally based on whether they have been logged before.
 */
public class LogCheckerClass {

    private SootClass clazz;
    private static LogCheckerClass instance;

    /**
     * Provides a global access point for the singleton instance of LogCheckerClass.
     * If the instance does not exist, it initializes a new instance.
     *
     * @return The singleton instance of LogCheckerClass.
     */
    public static LogCheckerClass v() {
        if (instance == null) {
            instance = new LogCheckerClass();
        }
        return instance;
    }

    /**
     * Generates a new SootClass for logging purposes and sets up its structure.
     * This includes defining the class, its fields, and methods for log management.
     */
    public void generateClass() {
        this.clazz = new SootClass(Constants.LOG_CHECKER_CLASS, Modifier.PUBLIC);
        this.clazz.setSuperclass(Scene.v().getSootClass(Constants.JAVA_LANG_OBJECT));
        SootField sf = new SootField(Constants.LOGS, RefType.v(Constants.JAVA_UTIL_SET), Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL);
        this.clazz.addField(sf);
        Scene.v().addClass(this.clazz);
        this.clazz.setApplicationClass();
        this.generateInitMethod();
        this.generateClInitMethod();
        this.generateAddLogMethod();
        this.generateAlreadyLoggedMethod();
        this.generateLogMethod();
    }

    /**
     * Generates the initializer method for the LogCheckerClass.
     * This method is responsible for setting up the initial state of the object.
     */
    private void generateInitMethod() {
        SootUtils su = new SootUtils();
        SootMethod sm = new SootMethod(Constants.INIT,
                new ArrayList<Type>(), VoidType.v(), Modifier.PUBLIC);
        JimpleBody body = Jimple.v().newBody(sm);
        LocalGenerator lg = Scene.v().createLocalGenerator(body);
        sm.setActiveBody(body);
        UnitPatchingChain units = body.getUnits();
        Local thisLocal = lg.generateLocal(RefType.v(Constants.LOG_CHECKER_CLASS));
        units.add(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newThisRef(RefType.v(Constants.LOG_CHECKER_CLASS))));
        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newSpecialInvokeExpr(thisLocal,
                        su.getMethodRef(Constants.JAVA_LANG_OBJECT, Constants.SUB_SIG_INIT))));
        units.add(Jimple.v().newReturnVoidStmt());
        body.validate();
        this.clazz.addMethod(sm);
    }

    /**
     * Generates a static initializer (class constructor) method for the LogCheckerClass.
     * This method is responsible for initializing any static fields or performing other static initialization tasks.
     */
    private void generateClInitMethod() {
        SootUtils su = new SootUtils();
        SootMethod sm = new SootMethod(Constants.CLINIT,
                new ArrayList<Type>(), VoidType.v(), Modifier.STATIC);
        JimpleBody body = Jimple.v().newBody(sm);
        LocalGenerator lg = Scene.v().createLocalGenerator(body);
        sm.setActiveBody(body);
        UnitPatchingChain units = body.getUnits();
        Local set = lg.generateLocal(RefType.v(Constants.JAVA_UTIL_HASHSET));
        set.setName("set");
        units.add(Jimple.v().newAssignStmt(set, Jimple.v().newNewExpr(RefType.v(Constants.JAVA_UTIL_HASHSET))));
        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newSpecialInvokeExpr(set,
                        su.getMethodRef(Constants.JAVA_UTIL_HASHSET, Constants.SUB_SIG_INIT))));
        units.add(Jimple.v().newAssignStmt(Jimple.v().newStaticFieldRef(this.clazz.getField(Constants.LOGS, RefType.v(Constants.JAVA_UTIL_SET)).makeRef()), set));
        units.add(Jimple.v().newReturnVoidStmt());
        body.validate();
        this.clazz.addMethod(sm);
    }

    /**
     * Dynamically generates the 'addLog' method in the LogChecker class.
     * This method is responsible for adding a given log message to a static log set.
     * It ensures that any new log message is recorded in the set for future reference and checks.
     * <p>
     * The dynamically generated method takes a single String parameter representing the log message to be added.
     */
    private void generateAddLogMethod() {
        SootUtils su = new SootUtils();
        List<Type> params = new ArrayList<>();
        params.add(RefType.v(Constants.JAVA_LANG_STRING));
        SootMethod sm = new SootMethod(Constants.ADD_LOG,
                params, VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        JimpleBody body = Jimple.v().newBody(sm);
        LocalGenerator lg = Scene.v().createLocalGenerator(body);
        sm.setActiveBody(body);
        UnitPatchingChain units = body.getUnits();
        Local log = lg.generateLocal(RefType.v(Constants.JAVA_LANG_STRING));
        log.setName("log");
        Local logs = lg.generateLocal(RefType.v(Constants.JAVA_UTIL_SET));
        logs.setName("logs");
        units.add(Jimple.v().newIdentityStmt(log, Jimple.v().newParameterRef(RefType.v(Constants.JAVA_LANG_STRING), 0)));
        units.add(Jimple.v().newAssignStmt(logs, Jimple.v().newStaticFieldRef(this.clazz.getField(Constants.LOGS, RefType.v(Constants.JAVA_UTIL_SET)).makeRef())));
        units.add(Jimple.v().newInvokeStmt(
                Jimple.v().newInterfaceInvokeExpr(logs,
                        su.getMethodRef(Constants.JAVA_UTIL_SET, Constants.SUB_SIG_JAVA_UTIL_SET_ADD), log)));
        units.add(Jimple.v().newReturnVoidStmt());
        body.validate();
        this.clazz.addMethod(sm);
    }

    /**
     * Dynamically generates a method named 'alreadyLogged' in the LogChecker class.
     * This method is used to determine if a particular log message has already been logged.
     * It checks the presence of a log message in a static set of logged messages.
     * <p>
     * The dynamically generated method takes a single String parameter representing the log message.
     * It returns a boolean value indicating whether the log message is already in the log set.
     */
    private void generateAlreadyLoggedMethod() {
        SootUtils su = new SootUtils();
        List<Type> params = new ArrayList<>();
        params.add(RefType.v(Constants.JAVA_LANG_STRING));
        SootMethod sm = new SootMethod(Constants.ALREADY_LOGGED,
                params, BooleanType.v(), Modifier.PUBLIC | Modifier.STATIC);
        JimpleBody body = Jimple.v().newBody(sm);
        LocalGenerator lg = Scene.v().createLocalGenerator(body);
        sm.setActiveBody(body);
        UnitPatchingChain units = body.getUnits();
        Local log = lg.generateLocal(RefType.v(Constants.JAVA_LANG_STRING));
        log.setName("log");
        Local logs = lg.generateLocal(RefType.v(Constants.JAVA_UTIL_SET));
        logs.setName("logs");
        Local bool = lg.generateLocal(BooleanType.v());
        bool.setName("bool");
        units.add(Jimple.v().newIdentityStmt(log, Jimple.v().newParameterRef(RefType.v(Constants.JAVA_LANG_STRING), 0)));
        units.add(Jimple.v().newAssignStmt(logs, Jimple.v().newStaticFieldRef(this.clazz.getField(Constants.LOGS, RefType.v(Constants.JAVA_UTIL_SET)).makeRef())));
        units.add(Jimple.v().newAssignStmt(bool,
                Jimple.v().newInterfaceInvokeExpr(logs,
                        su.getMethodRef(Constants.JAVA_UTIL_SET, Constants.SUB_SIG_JAVA_UTIL_SET_CONTAINS), log)));
        units.add(Jimple.v().newReturnStmt(bool));
        body.validate();
        this.clazz.addMethod(sm);
    }

    /**
     * Dynamically generates the 'log' method in the LogChecker class.
     * This method logs a message with a given tag only if the message has not been logged before.
     * It first checks if the log message is already in the log set using the 'alreadyLogged' method.
     * If the message is not already logged, it logs the message using Android's logging system and adds the message to the log set.
     * <p>
     * The dynamically generated method takes two parameters: a String representing the log message and another String for the tag.
     */
    private void generateLogMethod() {
        SootUtils su = new SootUtils();
        List<Type> params = new ArrayList<>();
        params.add(RefType.v(Constants.JAVA_LANG_STRING));
        params.add(RefType.v(Constants.JAVA_LANG_STRING));
        SootMethod sm = new SootMethod(Constants.LOG,
                params, VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        JimpleBody body = Jimple.v().newBody(sm);
        LocalGenerator lg = Scene.v().createLocalGenerator(body);
        sm.setActiveBody(body);
        UnitPatchingChain units = body.getUnits();
        Local log = lg.generateLocal(RefType.v(Constants.JAVA_LANG_STRING));
        log.setName("log");
        Local tag = lg.generateLocal(RefType.v(Constants.JAVA_LANG_STRING));
        log.setName("tag");
        Local bool = lg.generateLocal(BooleanType.v());
        bool.setName("bool");
        units.add(Jimple.v().newIdentityStmt(log, Jimple.v().newParameterRef(RefType.v(Constants.JAVA_LANG_STRING), 0)));
        units.add(Jimple.v().newIdentityStmt(tag, Jimple.v().newParameterRef(RefType.v(Constants.JAVA_LANG_STRING), 1)));

        List<Value> paramsAlreadyLogged = new ArrayList<>();
        paramsAlreadyLogged.add(log);
        units.add(Jimple.v().newAssignStmt(bool, Jimple.v().newStaticInvokeExpr(su.getMethodRef(Constants.LOG_CHECKER_CLASS, Constants.SUB_SIG_ALREADY_LOGGED), paramsAlreadyLogged)));
        Value condition = Jimple.v().newNeExpr(bool, IntConstant.v(0));
        IfStmt is = Jimple.v().newIfStmt(condition, (Unit) null);
        units.add(is);

        SootMethodRef logMethodRef = su.getMethodRef(Constants.ANDROID_UTIL_LOG, Constants.LOG_D);
        List<Value> paramsLog = new ArrayList<>();
        paramsLog.add(tag);
        paramsLog.add(log);
        Unit logUnit = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(logMethodRef, paramsLog));

        units.add(logUnit);
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(su.getMethodRef(Constants.LOG_CHECKER_CLASS, Constants.SUB_SIG_ADD_LOG), paramsAlreadyLogged)));
        Unit ret = Jimple.v().newReturnVoidStmt();
        units.add(ret);
        is.setTarget(ret);

        body.validate();
        this.clazz.addMethod(sm);
    }
}