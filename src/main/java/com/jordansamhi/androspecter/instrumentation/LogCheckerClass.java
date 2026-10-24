package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the {@code LogCheckerClass} runtime helper used by {@link Logger} in the current Soot scene.
 * Prefer {@link #v()} so repeated callers share the generator's helper identity. The implicit no-argument
 * constructor creates an independent generator; it does not generate a helper, and later generation can
 * conflict with a helper owned by a different generator.
 * <p>The generated helper deduplicates messages through its static set, regardless of tag, and emits them
 * through {@code android.util.Log.d}. Each runtime class-loader copy and each app process has a separate set;
 * all probes using the same copy share it. Successful sink calls are recorded afterward, so a failed set
 * update can permit a retry to emit again. Its public static {@code log}, {@code addLog}, and {@code alreadyLogged}
 * methods use explicit monitors on a shared set, preserving synchronization in JVM and DEX output.
 * The check, sink call, and set update in {@code log} are atomic with respect to these helper methods.
 * A sink failure releases the lock and propagates; the failed message is not marked as logged.
 * <p>This generator mutates live Soot objects and must be called on the owning analysis thread. Its Java
 * synchronization does not make concurrent use of Soot scenes safe, and it performs no context checks itself.
 */
public class LogCheckerClass {

    private SootClass clazz;
    private static LogCheckerClass instance;

    /**
     * Returns the shared generator without generating a helper or resolving runtime dependencies.
     * The generator survives {@code G.reset()}; {@link #generateClass()} detects the current scene and
     * creates a fresh helper when necessary.
     *
     * @return the process-wide helper generator
     */
    public static synchronized LogCheckerClass v() {
        if (instance == null) {
            instance = new LogCheckerClass();
        }
        return instance;
    }

    /**
     * Ensures this generator's helper exists as an application class in the current scene.
     * Reuses its own existing helper, replaces a phantom placeholder, and creates a fresh helper after
     * {@code G.reset()}. A non-phantom class with the same name owned by another generator or application
     * is rejected. Required Object, String, Set, HashSet, Log, and Throwable signatures are resolved from
     * the configured Soot classpath before helper creation; already sufficient signatures are reused.
     * Configure an Android-compatible classpath before calling this method; it does not initialize an APK.
     * Newly generated method bodies are validated. An existing owned helper with its {@code log} method
     * present is reused without revalidating its other members. Failures can leave partial scene mutations.
     *
     * @throws IllegalStateException if the helper name collides or a required runtime class or method
     *         signature cannot be resolved from the configured classpath
     * @throws RuntimeException if Soot rejects a generated body or another scene mutation
     */
    public synchronized void generateClass() {
        SootClass existing = Scene.v().getSootClassUnsafe(Constants.LOG_CHECKER_CLASS, false);
        if (existing != null) {
            if (existing == this.clazz && existing.declaresMethod(Constants.SUB_SIG_LOG)) {
                return;
            }
            if (existing != this.clazz && !existing.isPhantom()) {
                throw new IllegalStateException("Cannot generate logging helper: class "
                        + Constants.LOG_CHECKER_CLASS + " already exists in the current Soot scene");
            }
            Scene.v().removeClass(existing);
        }
        resolveRuntimeClass(Constants.JAVA_LANG_OBJECT, Constants.SUB_SIG_INIT);
        resolveRuntimeClass(Constants.JAVA_LANG_STRING);
        resolveRuntimeClass(Constants.JAVA_UTIL_SET, Constants.SUB_SIG_JAVA_UTIL_SET_ADD,
                Constants.SUB_SIG_JAVA_UTIL_SET_CONTAINS);
        resolveRuntimeClass(Constants.JAVA_UTIL_HASHSET, Constants.SUB_SIG_INIT);
        resolveRuntimeClass(Constants.ANDROID_UTIL_LOG, Constants.LOG_D);
        resolveRuntimeClass("java.lang.Throwable");
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

    /** Resolve only missing or incomplete dependencies, preserving already populated synthetic/test scenes. */
    private void resolveRuntimeClass(String name, String... requiredMethods) {
        SootClass runtimeClass = Scene.v().getSootClassUnsafe(name, false);
        boolean needsResolution = runtimeClass == null || runtimeClass.resolvingLevel() < SootClass.SIGNATURES;
        if (!needsResolution) {
            for (String signature : requiredMethods) {
                if (!runtimeClass.declaresMethod(signature)) needsResolution = true;
            }
        }
        if (needsResolution) {
            try {
                runtimeClass = Scene.v().forceResolve(name, SootClass.SIGNATURES);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Cannot resolve logging runtime class " + name
                        + "; configure Soot's Android platform classpath before instrumentation", ex);
            }
        }
        if (runtimeClass == null || runtimeClass.resolvingLevel() < SootClass.SIGNATURES) {
            throw new IllegalStateException("Logging runtime class has no resolved signatures: " + name);
        }
        for (String signature : requiredMethods) {
            requireMethodRef(name, signature);
        }
    }

    private SootMethodRef requireMethodRef(String className, String signature) {
        SootClass owner = Scene.v().getSootClassUnsafe(className, false);
        SootMethod method = owner == null ? null : owner.getMethodUnsafe(signature);
        if (method == null) {
            throw new IllegalStateException("Cannot resolve required logging method <" + className + ": "
                    + signature + ">; configure Soot's Android platform classpath before instrumentation");
        }
        return method.makeRef();
    }

    /**
     * Generates the initializer method for the LogCheckerClass.
     * This method is responsible for setting up the initial state of the object.
     */
    private void generateInitMethod() {
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
                        requireMethodRef(Constants.JAVA_LANG_OBJECT, Constants.SUB_SIG_INIT))));
        units.add(Jimple.v().newReturnVoidStmt());
        this.clazz.addMethod(sm);
        body.validate();
    }

    /**
     * Generates a static initializer (class constructor) method for the LogCheckerClass.
     * This method is responsible for initializing any static fields or performing other static initialization tasks.
     */
    private void generateClInitMethod() {
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
                        requireMethodRef(Constants.JAVA_UTIL_HASHSET, Constants.SUB_SIG_INIT))));
        units.add(Jimple.v().newAssignStmt(Jimple.v().newStaticFieldRef(this.clazz.getField(Constants.LOGS, RefType.v(Constants.JAVA_UTIL_SET)).makeRef()), set));
        units.add(Jimple.v().newReturnVoidStmt());
        this.clazz.addMethod(sm);
        body.validate();
    }

    /**
     * Dynamically generates the 'addLog' method in the LogChecker class.
     * This method is responsible for adding a given log message to a static log set.
     * It ensures that any new log message is recorded in the set for future reference and checks.
     * <p>
     * The dynamically generated method takes a single String parameter representing the log message to be added.
     */
    private void generateAddLogMethod() {
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
                        requireMethodRef(Constants.JAVA_UTIL_SET, Constants.SUB_SIG_JAVA_UTIL_SET_ADD), log)));
        units.add(Jimple.v().newReturnVoidStmt());
        synchronizeBody(body);
        this.clazz.addMethod(sm);
        body.validate();
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
                        requireMethodRef(Constants.JAVA_UTIL_SET, Constants.SUB_SIG_JAVA_UTIL_SET_CONTAINS), log)));
        units.add(Jimple.v().newReturnStmt(bool));
        synchronizeBody(body);
        this.clazz.addMethod(sm);
        body.validate();
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
        tag.setName("tag");
        Local bool = lg.generateLocal(BooleanType.v());
        bool.setName("bool");
        units.add(Jimple.v().newIdentityStmt(log, Jimple.v().newParameterRef(RefType.v(Constants.JAVA_LANG_STRING), 0)));
        units.add(Jimple.v().newIdentityStmt(tag, Jimple.v().newParameterRef(RefType.v(Constants.JAVA_LANG_STRING), 1)));

        List<Value> paramsAlreadyLogged = new ArrayList<>();
        paramsAlreadyLogged.add(log);
        units.add(Jimple.v().newAssignStmt(bool, Jimple.v().newStaticInvokeExpr(requireMethodRef(Constants.LOG_CHECKER_CLASS, Constants.SUB_SIG_ALREADY_LOGGED), paramsAlreadyLogged)));
        Value condition = Jimple.v().newNeExpr(bool, IntConstant.v(0));
        IfStmt is = Jimple.v().newIfStmt(condition, (Unit) null);
        units.add(is);

        SootMethodRef logMethodRef = requireMethodRef(Constants.ANDROID_UTIL_LOG, Constants.LOG_D);
        List<Value> paramsLog = new ArrayList<>();
        paramsLog.add(tag);
        paramsLog.add(log);
        Unit logUnit = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(logMethodRef, paramsLog));

        units.add(logUnit);
        units.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(requireMethodRef(Constants.LOG_CHECKER_CLASS, Constants.SUB_SIG_ADD_LOG), paramsAlreadyLogged)));
        Unit ret = Jimple.v().newReturnVoidStmt();
        units.add(ret);
        is.setTarget(ret);

        synchronizeBody(body);
        this.clazz.addMethod(sm);
        body.validate();
    }

    /**
     * Emits explicit monitor operations so synchronization is preserved in both JVM and DEX output.
     * The exception handler releases the lock before rethrowing a failure from the set or log sink.
     */
    private void synchronizeBody(JimpleBody body) {
        UnitPatchingChain units = body.getUnits();
        Unit first = body.getFirstNonIdentityStmt();
        LocalGenerator locals = Scene.v().createLocalGenerator(body);
        Local lock = locals.generateLocal(RefType.v(Constants.JAVA_UTIL_SET));
        Local failure = locals.generateLocal(RefType.v("java.lang.Throwable"));
        Unit loadLock = Jimple.v().newAssignStmt(lock,
                Jimple.v().newStaticFieldRef(this.clazz.getField(Constants.LOGS,
                        RefType.v(Constants.JAVA_UTIL_SET)).makeRef()));
        units.insertBeforeNoRedirect(loadLock, first);
        units.insertBeforeNoRedirect(Jimple.v().newEnterMonitorStmt(lock), first);
        for (java.util.Iterator<Unit> iterator = units.snapshotIterator(); iterator.hasNext();) {
            Unit unit = iterator.next();
            if (unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt) {
                units.insertBefore(Jimple.v().newExitMonitorStmt(lock), unit);
            }
        }
        Unit handler = Jimple.v().newIdentityStmt(failure, Jimple.v().newCaughtExceptionRef());
        units.add(handler);
        units.add(Jimple.v().newExitMonitorStmt(lock));
        units.add(Jimple.v().newThrowStmt(failure));
        body.getTraps().add(Jimple.v().newTrap(Scene.v().getSootClass("java.lang.Throwable"), first, handler, handler));
    }
}
