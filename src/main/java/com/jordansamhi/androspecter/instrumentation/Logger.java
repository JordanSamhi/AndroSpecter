package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.SootUtils;
import com.jordansamhi.androspecter.analysis.AnalysisContext;
import com.jordansamhi.androspecter.inspection.Inspection;
import com.jordansamhi.androspecter.inspection.LibraryFilter;
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
 * Instance-owned logging transformations for the current Soot scene. Construct a logger with an analysis
 * context, register operations such as {@link #logAllMethods(String, boolean)}, then submit it through the
 * managed analysis's transformation API. {@link #apply(AnalysisContext)} executes this instance's operations
 * in registration order, with each operation visiting all selected methods before the next begins.
 * Registrations remain after execution; applying twice can insert duplicate probes. New instances do not
 * register global Soot packs. The deprecated {@link #v()} adapter retains the legacy pack-based workflow.
 * <p>Class scope, method scope, and {@link #setTargetPackage(String)} are always enforced. An operation's
 * {@code includeLibraries=true} bypasses only the configured library classifier; it does not widen those
 * scopes. With default options, only Soot application classes are eligible and no classes are classified
 * as libraries. The generated helper class is always excluded from instrumentation.
 * <p>The logger is bound to its creating thread and Soot scene, and instance loggers also require their
 * original context to remain valid. Soot arguments are borrowed and must belong to that scene. Direct
 * insertion, application, and export operate on live state; they do not invalidate a facade's cached graph.
 * Submit mutations through the managed analysis's transformation API when graph rebuilding is needed.
 * <p>Generated probes call Android's debug logger through a helper that deduplicates successfully recorded
 * messages in its static set, regardless of tag; different app processes or class loaders have separate
 * sets. These messages describe coverage, not event counts or a complete trace, and their contents are
 * deduplicated across all logger instances that instrument the same helper.
 * Missing helper runtime signatures are resolved using the configured Soot classpath when a probe is inserted.
 * <p>For an already open {@code AndroidAnalysis analysis}, registration and execution are separate:
 * <pre>{@code
 * Logger logger = new Logger(analysis.context());
 * logger.setTargetPackage("com.example");
 * logger.logAllMethods("coverage", true);
 * analysis.apply(logger); // Mutates bodies and invalidates the managed graph cache.
 * }</pre>
 * This changes only the in-memory model. Export, APK signing, and execution are separate steps.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class Logger implements Transformation {

    private final SootUtils su;
    private final Scene scene;
    private final Thread ownerThread;
    private final AnalysisContext context;
    private final InstrumentationOptions configuration;
    private final List<BodyStep> steps = new ArrayList<>();

    private static Logger instance;

    // Field to specify the target package name
    private String targetPackage;

    /**
     * Legacy pack-based instance.
     */
    private Logger() {
        this(null, InstrumentationOptions.builder().classes(clazz -> true)
                .libraryFilter(LibraryFilter.knownLibraries()).build(), true);
    }

    /**
     * Creates a logger selecting Soot application classes and all their methods, with no library classifier.
     * Construction registers no Soot packs and inserts no helper or probes.
     *
     * @param context valid context that will own this logger's lifetime and thread
     * @throws NullPointerException if {@code context} is null
     * @throws IllegalStateException if the context is closed, belongs to another thread, or Soot was reset
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     */
    public Logger(AnalysisContext context) {
        this(context, InstrumentationOptions.builder().build());
    }

    /**
     * Creates a logger with explicitly configured class, method, and library policies.
     * Options and their predicate instances are retained. Construction accesses the current Soot Scene
     * singleton but does not add classes, alter bodies, or register packs.
     *
     * @param context valid context that will own this logger's lifetime and thread
     * @param options scope and library classification for this logger
     * @throws NullPointerException if either argument is null
     * @throws IllegalStateException if the context is closed, belongs to another thread, or Soot was reset
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     */
    public Logger(AnalysisContext context, InstrumentationOptions options) {
        this(Objects.requireNonNull(context, "context"), options, false);
    }

    private Logger(AnalysisContext context, InstrumentationOptions options, boolean legacy) {
        if (!legacy) context.checkValid();
        su = new SootUtils();
        scene = Scene.v();
        ownerThread = Thread.currentThread();
        this.context = context;
        configuration = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns the legacy logger for the current scene and immediately ensures its logging helper exists.
     * This adapter accepts all classes before package filtering and uses {@link LibraryFilter#knownLibraries()}.
     * Its registration methods add named transforms to Soot's global {@code jtp} pack; {@link #instrument()}
     * runs all Soot packs. Reacquire after {@code G.reset()} to discard the previous logger's configuration.
     * Repeated registration of the same operation uses the same phase name and may be rejected by Soot.
     * <p>The legacy transforms retain the creating-thread requirement. Soot's normal worker-backed body-pack
     * execution invokes them on another thread and is rejected even with one configured worker; use an
     * instance logger with managed {@code analysis.apply(logger)} for that workflow. Direct legacy insertion
     * calls on the creating thread are unaffected by this limitation.
     *
     * @return the legacy logger bound to the current scene and its first calling thread
     * @throws IllegalStateException if called from another thread, or the helper collides with an existing
     *         application class or cannot resolve required runtime signatures
     * @throws RuntimeException if the legacy library policy cannot be loaded or helper validation fails
     * @deprecated construct a logger with an {@link AnalysisContext} and apply transformations explicitly
     */
    @Deprecated
    public static synchronized Logger v() {
        if (instance == null || instance.scene != Scene.v()) {
            instance = new Logger();
        }
        instance.checkValid();
        LogCheckerClass.v().generateClass();
        return instance;
    }

    /**
     * Replaces the additional package restriction applied to subsequent instrumentation and direct insertions.
     * At execution time, accepted classes must be in this exact package or a subpackage, in addition to
     * satisfying the configured predicates. For example, {@code com.example} excludes {@code com.exampleother}.
     * Changing this value also affects operations already registered but not yet executed.
     * The value is used literally: whitespace and a trailing dot are not stripped or validated.
     *
     * @param targetPackage package name without a trailing dot; null or empty removes this restriction
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     */
    public void setTargetPackage(String targetPackage) {
        checkValid();
        this.targetPackage = targetPackage;
    }

    /**
     * Adds a transformation to the Jimple Transformation Pack (jtp) for a specific phase.
     *
     * @param phaseName The name of the phase during which this transformation should be applied.
     * @param logic     The logic to execute during the transformation, encapsulated as a Consumer of Body.
     */
    private void addTransformation(String phaseName, boolean includeLibraries, Consumer<Body> logic) {
        checkValid();
        steps.add(new BodyStep(includeLibraries, logic));
        if (context == null) {
            PackManager.v().getPack("jtp").add(new Transform(phaseName, new BodyTransformer() {
                /**
                 * Applies the legacy registration to an eligible body on the logger's owning thread.
                 * @param b live body supplied by Soot
                 * @param phaseName Soot phase name; the registration already captures its operation
                 * @param options Soot phase options; not interpreted by this operation
                 * @throws IllegalStateException if Soot dispatches on another thread or the scene is stale
                 */
                @Override
                protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                    checkValid();
                    if (acceptsMethod(b.getMethod()) && (includeLibraries || !isLibrary(b.getMethod().getDeclaringClass()))) {
                        logic.accept(b);
                        checkValid();
                    }
                }
            }));
        }
    }

    private void checkValid() {
        if (context != null) context.checkValid();
        if (Thread.currentThread() != ownerThread || scene != Scene.v()) {
            throw new IllegalStateException("Logger must be used on its owning thread and Soot scene");
        }
    }

    private boolean acceptsClass(SootClass clazz) {
        checkValid();
        if (clazz.getName().equals(Constants.LOG_CHECKER_CLASS)) return false;
        boolean accepted = configuration.classFilter().test(clazz);
        checkValid();
        if (!accepted) return false;
        String packageName = clazz.getPackageName();
        return targetPackage == null || targetPackage.isEmpty() || packageName.equals(targetPackage)
                || packageName.startsWith(targetPackage + ".");
    }

    private boolean acceptsMethod(SootMethod method) {
        if (!acceptsClass(method.getDeclaringClass())) return false;
        boolean accepted = configuration.methodFilter().test(method);
        checkValid();
        return accepted;
    }

    private boolean isLibrary(SootClass clazz) {
        checkValid();
        boolean library = configuration.libraryFilter().isLibrary(clazz);
        checkValid();
        return library;
    }

    private static final class BodyStep {
        private final boolean includeLibraries;
        private final Consumer<Body> action;

        private BodyStep(boolean includeLibraries, Consumer<Body> action) {
            this.includeLibraries = includeLibraries;
            this.action = action;
        }
    }

    /**
     * Applies a snapshot of this instance's registrations, in order, to the bound analysis.
     * Each operation completes over all selected concrete methods before the next operation begins, and
     * each changed body is validated. Earlier mutations remain visible to later operations. Bodies of
     * rejected classes or methods are not loaded. This does not clear registrations or run global Soot packs.
     * Failures propagate without rolling back earlier mutations; repeated calls may insert duplicate probes.
     * Scope predicates may be evaluated more than once for the same object, including during insertion,
     * and should not mutate the scene. Direct application does not invalidate managed graph caches; prefer
     * {@link com.jordansamhi.androspecter.analysis.AndroidAnalysis#apply(Transformation)}.
     *
     * @param context the same analysis context supplied to this logger's constructor
     * @throws NullPointerException if {@code context} is null
     * @throws IllegalArgumentException if the supplied context differs, or this is a legacy {@link #v()} logger
     * @throws IllegalStateException if a context is stale or on the wrong thread, or helper generation fails
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if a predicate, body load, insertion, or Soot validation fails
     */
    @Override
    public void apply(AnalysisContext context) {
        Objects.requireNonNull(context, "context").checkValid();
        checkValid();
        if (this.context == null || !this.context.id().equals(context.id())) {
            throw new IllegalArgumentException("Logger must be applied with the analysis context supplied to its constructor");
        }
        for (BodyStep step : List.copyOf(steps)) {
            Inspection.builder()
                    .classes(this::acceptsClass)
                    .classes(clazz -> step.includeLibraries || !isLibrary(clazz))
                    .methods(this::acceptsMethod)
                    .onMethod(method -> {
                        if (!method.isConcrete()) return;
                        Body body = method.retrieveActiveBody();
                        checkValid();
                        step.action.accept(body);
                        checkValid();
                        body.validate();
                    }).build().run(context);
        }
    }

    /**
     * Immediately inserts one probe before or after an existing unit and validates the body.
     * The method is skipped if its class, method, or target-package scope rejects it. This low-level operation
     * does not apply the library classifier. A before-insertion uses the supplied chain's branch-redirection
     * behavior; use the body's {@link soot.UnitPatchingChain} so incoming branches target the new probe.
     * An after-insertion is reached only if control falls through the insertion point; placing it after a
     * return, throw, or unconditional jump does not record that unit's execution. Before either insertion mode,
     * a return is appended for {@code void} or {@code java.lang.Void} bodies only when the body contains no
     * corresponding return instruction. This is not a general repair of invalid control flow.
     * Helper generation and body mutation are immediate and are not rolled back if validation fails.
     *
     * @param units nonempty unit chain belonging to {@code b}; normally {@code b.getUnits()}
     * @param insertionPoint existing unit in {@code units}, from this logger's current scene
     * @param tagToLog non-null Android debug-log tag
     * @param messageToLog non-null message, deduplicated at runtime independently of its tag
     * @param b non-null borrowed Jimple body to mutate and validate
     * @param after whether to insert after the unit; false inserts before it
     * @throws IllegalStateException if the logger is no longer valid or helper generation fails
     * @throws NullPointerException if a required body, chain, or logging string is null when used
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if supplied Soot objects are inconsistent, a predicate fails, or validation fails
     */
    public void addLogStatement(Chain<Unit> units, Unit insertionPoint, String tagToLog, String messageToLog, Body b, boolean after) {

        checkValid();
        if (!acceptsMethod(b.getMethod())) return;
        LogCheckerClass.v().generateClass();

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
     * Immediately inserts a probe before the method's first non-identity statement.
     * Null, non-concrete, or out-of-scope methods are ignored. Accepted concrete methods have their Jimple
     * bodies loaded and validated. This low-level operation applies class, method, and package scope but
     * does not exclude libraries through the library classifier.
     * The body must be Jimple and have a non-identity insertion point. No return/throw completion or
     * successful constructor initialization is implied by a method-entry coverage message.
     *
     * @param sm borrowed method from the current scene, or null for a no-op
     * @param tagToLog non-null Android debug-log tag used if a probe is inserted
     * @param messageToLog non-null message, deduplicated at runtime independently of its tag
     * @throws IllegalStateException if the logger is no longer valid or helper generation fails
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws ClassCastException if an accepted method's active body is not Jimple
     * @throws RuntimeException if a predicate, body load, insertion, or Soot validation fails
     */
    public void addLogToMethod(SootMethod sm, String tagToLog, String messageToLog) {
        checkValid();
        if (sm != null) {
            if (acceptsMethod(sm) && sm.isConcrete()) {
                Body b = sm.retrieveActiveBody();
                JimpleBody jb = (JimpleBody) b;
                Chain<Unit> units = b.getUnits();
                Unit entrypoint = jb.getFirstNonIdentityStmt();
                addLogStatement(units, entrypoint, tagToLog, messageToLog, b, false);
            }
        }
    }

    /**
     * Registers method-entry coverage logging for selected concrete methods.
     * When applied, each probe precedes the first non-identity statement and emits
     * {@code METHOD=method-signature}. Registration appends an instance operation or, for {@link #v()},
     * adds the named legacy {@code jtp.methodsLogger} transform; it does not insert probes immediately.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass the configured library classifier; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */
    public void logAllMethods(String tagToLog, boolean includeLibraries) {
        addTransformation("jtp.methodsLogger", includeLibraries, b -> {
            SootMethod sm = b.getMethod();
            if (!includeLibraries && isLibrary(sm.getDeclaringClass())) {
                return;
            }
            if (this.isLogCheckerClass(sm)) {
                return;
            }
            addLogToMethod(sm, tagToLog, String.format("METHOD=%s", sm.getSignature()));
        });
    }

    /**
     * Checks if the given method belongs to the log checker class.
     * This is used to determine if logging should be applied to the method.
     *
     * @param sm The SootMethod instance to be checked.
     * @return true if the method's declaring class is the log checker class, false otherwise.
     */
    private boolean isLogCheckerClass(SootMethod sm) {
        String className = sm.getDeclaringClass().getName();
        return className.equals(Constants.LOG_CHECKER_CLASS);
    }

    /**
     * Registers class coverage logging in selected instance constructors and static initializers.
     * When applied, probes emit {@code CLASS=class-name}; deduplication merges all constructors of the same
     * class into one coverage message. Classes without a selected concrete initializer receive no probe.
     * This appends an instance operation or registers the legacy {@code jtp.classesLogger} transform.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass the configured library classifier; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */
    public void logAllClasses(String tagToLog, boolean includeLibraries) {
        addTransformation("jtp.classesLogger", includeLibraries, b -> {
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && isLibrary(b.getMethod().getDeclaringClass())) {
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
     * Registers probes before invocations in selected method bodies, excluding calls to the logging helper.
     * Messages have the form {@code CALL=caller-signature-->callee-signature}. The callee is the resolved
     * method referenced by the invocation expression, not a dynamically observed dispatch target. A message
     * records that a call site was reached, even if the call later fails. Equal caller/callee messages across
     * different sites are deduplicated. Filtering applies to the caller; calls to library targets are included.
     * This appends an instance operation or registers the legacy {@code jtp.methodCallsLogger} transform.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass library classification of callers; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */

    public void logAllMethodCalls(String tagToLog, boolean includeLibraries) {
        addTransformation("jtp.methodCallsLogger", includeLibraries, b -> {
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
            if (!includeLibraries && isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            Chain<Unit> units = b.getUnits();
            Map<Unit, String> insertionPointsToMessage = new HashMap<>();
            for (Unit u : units) {
                Stmt stmt = (Stmt) u;
                InvokeExpr ie;
                if (stmt.containsInvokeExpr()) {
                    ie = stmt.getInvokeExpr();
                    if (this.isLogCheckerClass(ie.getMethod())) {
                        continue;
                    }
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
     * Internal method to log specific Android components based on given parameters.
     * Logs are added to methods that match the specified component type and are part of the provided set of methods.
     * Messages have the form {@code TYPE=class|callback-subsignature} and are deduplicated per process:
     * they report coverage of each callback, not the number or order of lifecycle events.
     *
     * @param tagToLog         Tag to be used in the log statement.
     * @param phaseName        Phase during which this method is invoked.
     * @param componentType    Type of the Android component (e.g., Activity, Service, etc.).
     * @param methods          Set of method signatures to be logged within the specified component.
     * @param includeLibraries A boolean flag to determine whether library methods should be included in the logging.
     *                         If true, logging will include methods in both application and library Android components.
     *                         If false, only methods in application Android components will be logged.
     */
    private void logAndroidComponent(String tagToLog, String phaseName, String componentType, Set<String> methods, boolean includeLibraries) {
        addTransformation(phaseName, includeLibraries, b -> {
            if (!includeLibraries && isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            SootMethod sm = b.getMethod();
            SootClass sc = sm.getDeclaringClass();
            String actualComponentType = su.getComponentType(sc);
            if (actualComponentType.equals(componentType) && methods.contains(sm.getSubSignature())) {
                // Include the callback so deduplication preserves coverage of each lifecycle method.
                addLogToMethod(sm, tagToLog, String.format("%s=%s|%s", componentType.toUpperCase(Locale.ROOT),
                        sc.getName(), sm.getSubSignature()));
            }
        });
    }

    /**
     * Registers entry probes for selected Activity callbacks: {@code onCreate(Bundle)} and
     * {@code onCreate(Bundle, PersistableBundle)},
     * {@code onStart}, {@code onRestart}, {@code onStateNotSaved}, {@code onResume}, {@code onPause},
     * {@code onStop}, and {@code onDestroy}. Existing concrete declarations are instrumented; inherited
     * callbacks are not synthesized. Messages use {@code ACTIVITY=class-name|callback-subsignature},
     * recording callback coverage once per process rather than lifecycle order or event counts.
     * This appends an instance operation or registers the legacy {@code jtp.activitiesLogger} transform.
     * Classification uses the declaring class's superclass chain, not manifest declarations; abstract base
     * classes with selected concrete callbacks can be instrumented too.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass the configured library classifier; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */
    public void logActivities(String tagToLog, boolean includeLibraries) {
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
        logAndroidComponent(tagToLog, "jtp.activitiesLogger", Constants.ACTIVITY, methods, includeLibraries);
    }

    /**
     * Registers entry probes for selected Service declarations of {@code onCreate}, {@code onStart},
     * {@code onStartCommand}, {@code onDestroy}, {@code onBind}, {@code onUnbind}, and {@code onRebind}.
     * Messages use {@code SERVICE=class-name|callback-subsignature} and record callback coverage once per
     * process. Missing or inherited callbacks are not synthesized. This appends an instance operation or
     * registers the legacy {@code jtp.servicesLogger} transform.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass the configured library classifier; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */
    public void logServices(String tagToLog, boolean includeLibraries) {
        Set<String> methods = new HashSet<>();
        methods.add(Constants.ONCREATE_SERVICE);
        methods.add(Constants.ONSTART_SERVICE);
        methods.add(Constants.ONSTARTCOMMAND_SERVICE);
        methods.add(Constants.ONDESTROY_SERVICE);
        methods.add(Constants.ONBIND_SERVICE);
        methods.add(Constants.ONUNBIND_SERVICE);
        methods.add(Constants.ONREBIND_SERVICE);
        logAndroidComponent(tagToLog, "jtp.servicesLogger", Constants.SERVICE, methods, includeLibraries);
    }

    /**
     * Registers entry probes for selected BroadcastReceiver declarations of {@code onReceive}.
     * Messages use {@code BROADCASTRECEIVER=class-name|callback-subsignature} and record coverage once per
     * process. Missing or inherited callbacks are not synthesized. This appends an instance operation or
     * registers the legacy {@code jtp.broadcastReceiversLogger} transform.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass the configured library classifier; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */
    public void logBroadcastReceivers(String tagToLog, boolean includeLibraries) {
        Set<String> methods = new HashSet<>();
        methods.add(Constants.ONRECEIVE);
        logAndroidComponent(tagToLog, "jtp.broadcastReceiversLogger", Constants.BROADCAST_RECEIVER, methods, includeLibraries);
    }

    /**
     * Registers entry probes for selected ContentProvider declarations of {@code onCreate}.
     * Messages use {@code CONTENTPROVIDER=class-name|callback-subsignature} and record coverage once per
     * process. Missing or inherited callbacks are not synthesized. This appends an instance operation or
     * registers the legacy {@code jtp.contentProvidersLogger} transform.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass the configured library classifier; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */
    public void logContentProviders(String tagToLog, boolean includeLibraries) {
        Set<String> methods = new HashSet<>();
        methods.add(Constants.ONCREATE_CONTENT_PROVIDER);
        logAndroidComponent(tagToLog, "jtp.contentProvidersLogger", Constants.CONTENT_PROVIDER, methods, includeLibraries);
    }

    /**
     * Registers coverage probes before units in selected bodies, excluding identities, returns, and monitors.
     * Probes precede jumps and throws, with incoming branches redirected to the probe, so reaching a statement
     * is recorded even if it does not complete. Messages use {@code STATEMENT=method|statement|position};
     * position is the one-based unit index when this operation runs, including units excluded from probing.
     * The operation snapshots the body before inserting its probes; probes from earlier operations may
     * therefore be included in this snapshot. Messages are deduplicated at runtime, not counted.
     * This appends an instance operation or registers the legacy {@code jtp.statementsLogger} transform.
     *
     * @param tagToLog non-null Android debug-log tag to embed when the operation runs
     * @param includeLibraries whether to bypass the configured library classifier; all scope predicates still apply
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot rejects registration of a duplicate legacy phase name
     */
    public void logAllStatements(String tagToLog, boolean includeLibraries) {
        addTransformation("jtp.statementsLogger", includeLibraries, b -> {
            if (!includeLibraries && isLibrary(b.getMethod().getDeclaringClass())) {
                return;
            }
            if (this.isLogCheckerClass(b.getMethod())) {
                return;
            }
            Chain<Unit> units = b.getUnits();
            Map<Unit, String> insertionPointsToMessage = new LinkedHashMap<>();
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
                addLogStatement(units, e.getKey(), tagToLog, e.getValue(), b, false);
            }
        });
    }

    /**
     * Executes instrumentation using this logger's workflow.
     * An instance logger delegates to {@link #apply(AnalysisContext)} with its bound context, preserving
     * operation order. A legacy {@link #v()} logger runs all configured Soot packs, including unrelated
     * transforms and Soot's usual pack ordering. Registrations remain installed; this is not idempotent.
     * Use the managed analysis's transformation API instead when its cached call graph must be invalidated.
     * Soot's normal body-pack executor uses worker threads, which violate the legacy logger's creating-thread
     * check and cause an exception when a registered transform is invoked. Setting one worker does not move
     * execution back to the calling thread. Instance loggers execute their registrations synchronously.
     *
     * @throws IllegalStateException if the logger is no longer valid, helper generation fails, or a legacy
     *         transform is invoked on a Soot worker thread
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if a transformation, Soot pack, or body validation fails
     */
    public void instrument() {
        checkValid();
        if (context == null) {
            PackManager.v().runPacks();
        } else {
            apply(context);
        }
        checkValid();
    }

    /**
     * Writes the current scene through Soot using its existing output-format and output-directory options.
     * This does not apply pending logger operations. The output is an APK only when Soot's input and output
     * configuration requests one; the method performs no signing or installation. Output failures can leave
     * partially written files. Cancellation is checked before and after Soot's write operation.
     * Soot may convert or release active bodies during output; set
     * {@code Options.v().set_no_writeout_body_releasing(true)} beforehand if bodies must be retained,
     * and do not assume previously borrowed body/unit references still describe the current representation.
     *
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if Soot cannot convert or write the configured output
     */
    public void exportNewApk() {
        checkValid();
        PackManager.v().writeOutput();
        checkValid();
    }

    /**
     * Sets Soot's global output format to DEX and writes the current scene into the supplied directory.
     * The global format and directory remain changed afterward. This does not execute pending logging
     * operations, sign an APK, or install it. Soot determines whether the output is an APK container or DEX
     * files from the loaded input configuration. Cancellation is checked before and after the write.
     * Output is subject to Soot's existing overwrite policy and may leave partial files on failure.
     * Like {@link #exportNewApk()}, this may convert or release active bodies according to Soot options.
     *
     * @param path non-null output-directory path, not a destination APK filename
     * @throws IllegalStateException if this logger's thread, scene, or context is no longer valid
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if the path is invalid or Soot cannot convert or write the output
     */
    public void exportNewApk(String path) {
        checkValid();
        Options.v().set_output_format(Options.output_format_dex);
        Options.v().set_output_dir(path);
        PackManager.v().writeOutput();
        checkValid();
    }
}
