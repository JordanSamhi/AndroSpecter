package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.utils.Constants;
import com.jordansamhi.androspecter.analysis.AnalysisContext;
import com.jordansamhi.androspecter.inspection.LibraryFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import soot.*;
import soot.baf.BafASMBackend;
import soot.jimple.*;
import soot.options.Options;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toDex.DexPrinter;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoggerTest {
    @BeforeEach
    void setUp() {
        G.reset();
        installPlatformStubs();
    }

    @AfterEach
    void tearDown() {
        G.reset();
    }

    @Test
    void statementProbesCoverBothBranchesJumpsAndThrows() {
        SootClass owner = newClass("example.Target", Scene.v().getSootClass("java.lang.Object"));
        SootMethod method = new SootMethod("run", Arrays.asList(IntType.v(), RefType.v("java.lang.Throwable"),
                RefType.v("java.lang.Object")), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        Local flag = parameter(body, "flag", IntType.v(), 0);
        Local error = parameter(body, "error", RefType.v("java.lang.Throwable"), 1);
        Local lock = parameter(body, "lock", RefType.v("java.lang.Object"), 2);
        Unit enter = Jimple.v().newEnterMonitorStmt(lock);
        Unit exit = Jimple.v().newExitMonitorStmt(lock);
        ThrowStmt throwing = Jimple.v().newThrowStmt(error);
        NopStmt destination = Jimple.v().newNopStmt();
        IfStmt branch = Jimple.v().newIfStmt(Jimple.v().newEqExpr(flag, IntConstant.v(0)), throwing);
        GotoStmt jump = Jimple.v().newGotoStmt(destination);
        Unit returning = Jimple.v().newReturnVoidStmt();
        body.getUnits().addAll(Arrays.asList(enter, exit, branch, jump, throwing, destination, returning));
        body.validate();

        Logger.v().logAllStatements("coverage", true);
        apply("jtp.statementsLogger", body);
        body.validate();

        BriefUnitGraph graph = new BriefUnitGraph(body);
        for (Unit original : Arrays.asList(branch, jump, throwing, destination)) {
            Unit probe = body.getUnits().getPredOf(original);
            assertTrue(message(probe).startsWith("STATEMENT="));
            assertEquals(Collections.singletonList(probe), graph.getPredsOf(original));
            assertEquals(Collections.singletonList(original), graph.getSuccsOf(probe));
        }
        Unit throwProbe = body.getUnits().getPredOf(throwing);
        Unit jumpProbe = body.getUnits().getPredOf(jump);
        Unit destinationProbe = body.getUnits().getPredOf(destination);
        assertEquals(new HashSet<>(Arrays.asList(throwProbe, jumpProbe)), new HashSet<>(graph.getSuccsOf(branch)));
        assertSame(throwProbe, branch.getTarget());
        assertSame(destinationProbe, jump.getTarget());
        assertTrue(graph.getSuccsOf(throwing).isEmpty());
        assertSame(exit, body.getUnits().getSuccOf(enter));
        assertTrue(body.getUnits().getPredOf(enter) instanceof IdentityStmt);
        assertSame(returning, body.getUnits().getSuccOf(destination));
        assertEquals(4, body.getUnits().stream().filter(this::isProbe).count());
    }

    @Test
    void packageFilterUsesPackageBoundaries() {
        Logger logger = Logger.v();
        logger.setTargetPackage("com.example");
        for (String name : Arrays.asList("com.example.Target", "com.example.child.Target", "com.exampleother.Target")) {
            JimpleBody body = emptyMethod(newClass(name, Scene.v().getSootClass("java.lang.Object")), "run");
            logger.addLogToMethod(body.getMethod(), "tag", name);
            body.validate();
            assertEquals(name.startsWith("com.example."), body.getUnits().stream().anyMatch(this::isProbe));
        }
    }

    @Test
    void lifecycleMessagesDistinguishCallbacks() {
        SootClass activity = newClass("android.app.Activity", Scene.v().getSootClass("java.lang.Object"));
        SootClass owner = newClass("example.MainActivity", activity);
        JimpleBody start = emptyMethod(owner, "onStart");
        JimpleBody stop = emptyMethod(owner, "onStop");
        Logger.v().logActivities("lifecycle", true);
        apply("jtp.activitiesLogger", start);
        apply("jtp.activitiesLogger", stop);
        start.validate();
        stop.validate();
        assertEquals("ACTIVITY=example.MainActivity|void onStart()", message(start.getUnits().getFirst()));
        assertEquals("ACTIVITY=example.MainActivity|void onStop()", message(stop.getUnits().getFirst()));
    }

    @Test
    void loggerCanBeInitializedRepeatedlyAndAfterSootReset() {
        Logger first = Logger.v();
        first.setTargetPackage("previous.application");
        SootClass firstHelper = Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS);
        assertSame(first, Logger.v());
        assertSame(firstHelper, Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS));
        assertEquals(5, firstHelper.getMethodCount());

        G.reset();
        installPlatformStubs();
        Logger second = Logger.v();
        assertNotSame(first, second);
        SootClass secondHelper = Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS);
        assertNotSame(firstHelper, secondHelper);
        secondHelper.getMethods().forEach(method -> method.retrieveActiveBody().validate());
        JimpleBody body = emptyMethod(newClass("next.application.Target", Scene.v().getSootClass("java.lang.Object")), "run");
        second.addLogToMethod(body.getMethod(), "tag", "next");
        assertTrue(isProbe(body.getUnits().getFirst()), "The previous scene's package filter must not leak");
    }

    @Test
    void helperReplacesPhantomButRejectsAnExistingApplicationClass() {
        SootClass placeholder = new SootClass(Constants.LOG_CHECKER_CLASS);
        Scene.v().addClass(placeholder);
        placeholder.setPhantomClass();
        Logger.v();
        assertFalse(Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS).isPhantom());

        G.reset();
        installPlatformStubs();
        SootClass collision = newClass(Constants.LOG_CHECKER_CLASS, Scene.v().getSootClass("java.lang.Object"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, Logger::v);
        assertTrue(failure.getMessage().contains("already exists"));
        assertSame(collision, Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS));
    }

    @Test
    void helperLoadsAbsentRuntimeDependenciesFromConfiguredClasspath(@TempDir Path classpath) throws Exception {
        SootClass hashSet = Scene.v().getSootClass(Constants.JAVA_UTIL_HASHSET);
        SootMethod constructor = hashSet.getMethod(Constants.SUB_SIG_INIT);
        JimpleBody body = Jimple.v().newBody(constructor);
        constructor.setActiveBody(body);
        Local self = Jimple.v().newLocal("self", hashSet.getType());
        body.getLocals().add(self);
        body.getUnits().add(Jimple.v().newIdentityStmt(self, Jimple.v().newThisRef(hashSet.getType())));
        body.getUnits().add(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(self,
                Scene.v().getSootClass(Constants.JAVA_LANG_OBJECT).getMethod(Constants.SUB_SIG_INIT).makeRef())));
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        for (String name : List.of(Constants.JAVA_UTIL_HASHSET, Constants.ANDROID_UTIL_LOG)) {
            SootClass dependency = Scene.v().getSootClass(name);
            Path classFile = classpath.resolve(name.replace('.', '/') + ".class");
            Files.createDirectories(classFile.getParent());
            Files.write(classFile, bytecode(dependency));
            Scene.v().removeClass(dependency);
        }
        Scene.v().setSootClassPath(classpath.toString());
        assertFalse(Scene.v().containsClass(Constants.JAVA_UTIL_HASHSET));
        assertFalse(Scene.v().containsClass(Constants.ANDROID_UTIL_LOG));

        Logger.v();

        assertTrue(Scene.v().getSootClass(Constants.JAVA_UTIL_HASHSET).declaresMethod(Constants.SUB_SIG_INIT));
        assertTrue(Scene.v().getSootClass(Constants.ANDROID_UTIL_LOG).declaresMethod(Constants.LOG_D));
        Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS).getMethods().forEach(method -> method.retrieveActiveBody().validate());
    }

    @Test
    void missingRuntimeMethodFailsClearlyBeforeAddingTheHelper(@TempDir Path classpath) {
        SootClass androidLog = Scene.v().getSootClass(Constants.ANDROID_UTIL_LOG);
        androidLog.removeMethod(androidLog.getMethod(Constants.LOG_D));
        Scene.v().setSootClassPath(classpath.toString());
        Options.v().set_allow_phantom_refs(true);

        IllegalStateException failure = assertThrows(IllegalStateException.class, Logger::v);

        assertTrue(failure.getMessage().contains("android.util.Log"));
        assertFalse(Scene.v().containsClass(Constants.LOG_CHECKER_CLASS));
    }

    @Test
    void independentLoggersKeepFiltersAndRegistrationsOutOfGlobalPacks() {
        SootClass firstClass = newClass("example.First", Scene.v().getSootClass("java.lang.Object"));
        SootClass secondClass = newClass("example.Second", Scene.v().getSootClass("java.lang.Object"));
        firstClass.setApplicationClass();
        secondClass.setApplicationClass();
        JimpleBody firstBody = emptyMethod(firstClass, "run");
        JimpleBody secondBody = emptyMethod(secondClass, "run");
        try (AnalysisContext context = AnalysisContext.capture()) {
            Logger first = new Logger(context, InstrumentationOptions.builder().classes(c -> c == firstClass).build());
            Logger second = new Logger(context, InstrumentationOptions.builder().classes(c -> c == secondClass).build());
            first.logAllMethods("first-tag", true);
            second.logAllMethods("second-tag", true);
            assertNull(PackManager.v().getPack("jtp").get("jtp.methodsLogger"));
            first.apply(context);
            assertEquals(1, firstBody.getUnits().stream().filter(this::isProbe).count());
            assertEquals(0, secondBody.getUnits().stream().filter(this::isProbe).count());
            second.apply(context);
            assertEquals(1, firstBody.getUnits().stream().filter(this::isProbe).count());
            assertEquals(1, secondBody.getUnits().stream().filter(this::isProbe).count());
            assertEquals("first-tag", ((StringConstant) ((Stmt) firstBody.getUnits().getFirst()).getInvokeExpr().getArg(1)).value);
            assertEquals("second-tag", ((StringConstant) ((Stmt) secondBody.getUnits().getFirst()).getInvokeExpr().getArg(1)).value);
        }
        firstBody.validate();
        secondBody.validate();
    }

    @Test
    void instanceScopeAndCustomLibraryFilterPreventUnselectedBodyLoading() {
        SootClass selected = newClass("example.Selected", Scene.v().getSootClass("java.lang.Object"));
        SootClass library = newClass("example.Library", Scene.v().getSootClass("java.lang.Object"));
        SootClass rejected = newClass("ignored.Other", Scene.v().getSootClass("java.lang.Object"));
        JimpleBody selectedBody = emptyMethod(selected, "visit");
        SootMethod skippedMethod = new SootMethod("skip", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        SootMethod libraryMethod = new SootMethod("visit", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        SootMethod rejectedMethod = new SootMethod("visit", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        selected.addMethod(skippedMethod);
        library.addMethod(libraryMethod);
        rejected.addMethod(rejectedMethod);
        InstrumentationOptions options = InstrumentationOptions.builder()
                .classes(c -> c.getName().startsWith("example."))
                .methods(m -> m.getName().equals("visit"))
                .libraryFilter(LibraryFilter.from(c -> c == library)).build();
        try (AnalysisContext context = AnalysisContext.capture()) {
            Logger logger = new Logger(context, options);
            logger.logAllMethods("tag", false);
            logger.apply(context);
        }
        assertTrue(isProbe(selectedBody.getUnits().getFirst()));
        assertFalse(skippedMethod.hasActiveBody());
        assertFalse(libraryMethod.hasActiveBody());
        assertFalse(rejectedMethod.hasActiveBody());
    }

    @Test
    void instanceOperationsRunInOrderWithoutLoggingPreviouslyInsertedHelperCalls() {
        SootClass owner = newClass("example.Target", Scene.v().getSootClass("java.lang.Object"));
        owner.setApplicationClass();
        JimpleBody body = emptyMethod(owner, "run");
        SootMethod callee = new SootMethod("nativeTarget", Collections.emptyList(), VoidType.v(),
                Modifier.PUBLIC | Modifier.STATIC | Modifier.NATIVE);
        owner.addMethod(callee);
        Unit call = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(callee.makeRef()));
        body.getUnits().insertBefore(call, body.getUnits().getFirst());
        try (AnalysisContext context = AnalysisContext.capture()) {
            Logger logger = new Logger(context);
            logger.logAllMethods("tag", true);
            logger.logAllMethodCalls("tag", true);
            logger.apply(context);
        }
        List<String> messages = new ArrayList<>();
        body.getUnits().stream().filter(this::isProbe).forEach(unit -> messages.add(message(unit)));
        assertEquals(List.of("METHOD=" + body.getMethod().getSignature(),
                "CALL=" + body.getMethod().getSignature() + "-->" + callee.getSignature()), messages);
        body.validate();
    }

    @Test
    void libraryPredicateResetStopsBeforeLoadingTheBody() {
        SootClass owner = newClass("example.Target", Scene.v().getSootClass("java.lang.Object"));
        owner.setApplicationClass();
        SootMethod noBody = new SootMethod("run", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(noBody);
        try (AnalysisContext context = AnalysisContext.capture()) {
            Logger logger = new Logger(context, InstrumentationOptions.builder().libraryFilter(c -> {
                G.reset();
                return false;
            }).build());
            logger.logAllMethods("tag", false);
            assertThrows(IllegalStateException.class, () -> logger.apply(context));
        }
        assertFalse(noBody.hasActiveBody());
    }

    @Test
    void instanceLoggerRejectsAnotherContextAndSootReset() {
        try (AnalysisContext context = AnalysisContext.capture(); AnalysisContext other = AnalysisContext.capture()) {
            Logger logger = new Logger(context);
            assertThrows(IllegalArgumentException.class, () -> logger.apply(other));
            G.reset();
            assertThrows(IllegalStateException.class, () -> logger.logAllMethods("tag", true));
        }
    }

    @Test
    void instanceLoggerRetainsSubclassInsertionHooks() {
        SootClass owner = newClass("example.Target", Scene.v().getSootClass("java.lang.Object"));
        owner.setApplicationClass();
        JimpleBody body = emptyMethod(owner, "run");
        int[] calls = new int[2];
        try (AnalysisContext context = AnalysisContext.capture()) {
            Logger logger = new Logger(context) {
                @Override
                public void addLogToMethod(SootMethod method, String tag, String message) {
                    calls[0]++;
                    super.addLogToMethod(method, tag, message);
                }

                @Override
                public void addLogStatement(soot.util.Chain<Unit> units, Unit insertionPoint, String tag,
                                            String message, Body target, boolean after) {
                    calls[1]++;
                    super.addLogStatement(units, insertionPoint, tag, message, target, after);
                }
            };
            logger.logAllMethods("coverage", true);
            logger.instrument();
            assertArrayEquals(new int[]{1, 1}, calls);
            assertEquals(1, body.getUnits().stream().filter(this::isProbe).count());

            logger.addLogToMethod(body.getMethod(), "direct", "direct-message");
            assertArrayEquals(new int[]{2, 2}, calls,
                    "Direct method insertion must retain virtual dispatch to the statement hook");
            assertEquals(2, body.getUnits().stream().filter(this::isProbe).count());
            body.validate();
        }
    }

    @Test
    void legacyInstrumentRunsOnOneSootWorkerWithoutOpeningPublicAccess() throws Exception {
        assertLegacyInstrumentWorkerExecution(1);
    }

    @Test
    void legacyInstrumentRunsOnMultipleSootWorkersWithoutOpeningPublicAccess() throws Exception {
        assertLegacyInstrumentWorkerExecution(2);
    }

    @Test
    void legacyInstrumentPropagatesCallbackFailureAndRevokesWorkerAccess() throws Exception {
        configureSyntheticBodyPacks(1);
        SootClass owner = newClass("example.Failing", Scene.v().getSootClass("java.lang.Object"));
        owner.setApplicationClass();
        JimpleBody body = emptyMethod(owner, "run");
        Logger logger = Logger.v();
        logger.setTargetPackage("example");
        // Registration succeeds; constructing the probe's StringConstant fails inside the worker callback.
        logger.logAllMethods(null, true);
        Pack pack = PackManager.v().getPack("jtp");
        BodyTransformer original = (BodyTransformer) pack.get("jtp.methodsLogger").getTransformer();
        pack.remove("jtp.methodsLogger");
        Thread ownerThread = Thread.currentThread();
        Set<Thread> failedWorkers = ConcurrentHashMap.newKeySet();
        pack.add(new Transform("jtp.methodsLogger", new BodyTransformer() {
            @Override
            protected void internalTransform(Body current, String phaseName, Map<String, String> options) {
                if (current.getMethod() != body.getMethod()) {
                    original.transform(current, phaseName, options);
                    return;
                }
                assertNotSame(ownerThread, Thread.currentThread());
                NullPointerException failure = assertThrows(NullPointerException.class,
                        () -> original.transform(current, phaseName, options));
                // Test cleanup on the exact callback thread, before PackManager returns to the owner.
                assertThrows(IllegalStateException.class, () -> logger.setTargetPackage("changed"));
                assertThrows(IllegalStateException.class,
                        () -> logger.addLogToMethod(current.getMethod(), "tag", "unexpected"));
                failedWorkers.add(Thread.currentThread());
                throw failure;
            }
        }));

        assertThrows(NullPointerException.class, logger::instrument);
        // Soot/Heros interrupts the waiting owner when a worker fails. Production preserves that flag;
        // clear it only in this test so the subsequent foreign-thread guard checks can wait on their future.
        Thread.interrupted();

        assertEquals(1, failedWorkers.size());
        assertEquals(0, body.getUnits().stream().filter(this::isProbe).count());
        assertThrows(IllegalStateException.class, logger::instrument,
                "A failed legacy run must require a fresh Soot scene before another run");
        assertForeignThreadAccessRejected(logger, original, body);
        body.validate();
    }

    @Test
    void legacyInstrumentRejectsDelayedWorkerAfterAnotherWorkerFails() throws Exception {
        configureSyntheticBodyPacks(2);
        SootClass failing = newClass("example.Failing", Scene.v().getSootClass("java.lang.Object"));
        SootClass delayed = newClass("example.Delayed", Scene.v().getSootClass("java.lang.Object"));
        failing.setApplicationClass();
        delayed.setApplicationClass();
        JimpleBody failingBody = emptyMethod(failing, "run");
        JimpleBody delayedBody = emptyMethod(delayed, "run");
        Logger logger = Logger.v();
        logger.setTargetPackage("example");
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicReference<Thread> delayedThread = new AtomicReference<>();
        AtomicReference<Throwable> coordinationFailure = new AtomicReference<>();
        AtomicReference<Throwable> delayedFailure = new AtomicReference<>();
        Pack pack = PackManager.v().getPack("jtp");
        pack.add(new Transform("jtp.delayBeforeLogger", new BodyTransformer() {
            @Override
            protected void internalTransform(Body body, String phaseName, Map<String, String> options) {
                if (body.getMethod() == delayedBody.getMethod()) {
                    delayedThread.set(Thread.currentThread());
                    workerEntered.countDown();
                    // Soot shuts down its executor on the other worker's failure. Keep this worker parked
                    // until the owner has observed that failure and tried to restart the poisoned logger.
                    if (!awaitLatchDespiteInterrupts(releaseWorker)) {
                        coordinationFailure.set(new AssertionError("Owner did not release the delayed Soot worker"));
                    }
                } else if (body.getMethod() == failingBody.getMethod()
                        && !awaitLatchDespiteInterrupts(workerEntered)) {
                    coordinationFailure.set(new AssertionError("The second Soot worker never entered its before phase"));
                }
            }
        }));
        logger.logAllMethods(null, true);
        BodyTransformer original = (BodyTransformer) pack.get("jtp.methodsLogger").getTransformer();
        pack.remove("jtp.methodsLogger");
        pack.add(new Transform("jtp.methodsLogger", new BodyTransformer() {
            @Override
            protected void internalTransform(Body body, String phaseName, Map<String, String> options) {
                if (body.getMethod() != delayedBody.getMethod()) {
                    original.transform(body, phaseName, options);
                    return;
                }
                try {
                    original.transform(body, phaseName, options);
                } catch (Throwable failure) {
                    // Record on the owner: rethrowing here would cause another Soot interrupt during cleanup.
                    delayedFailure.set(failure);
                } finally {
                    callbackFinished.countDown();
                }
            }
        }));

        try {
            assertThrows(NullPointerException.class, logger::instrument);
            Thread.interrupted(); // Consume Soot's failure notification only in the test.
            assertEquals(0, workerEntered.getCount());
            assertEquals(1, callbackFinished.getCount(), "The delayed callback must still be held after owner failure");
            assertThrows(IllegalStateException.class, logger::instrument);
        } finally {
            releaseWorker.countDown();
            Thread worker = delayedThread.get();
            if (worker != null) {
                // Join the actual executor thread, not only its callback, before @AfterEach resets global Soot.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (worker.isAlive() && System.nanoTime() < deadline) {
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(worker, Math.max(1, deadline - System.nanoTime()));
                    } catch (InterruptedException ignored) {
                        // Test cleanup must finish even if Soot's owner notification arrives late.
                    }
                }
                assertFalse(worker.isAlive(), "The delayed Soot worker must exit before scene teardown");
            }
            Thread.interrupted();
        }

        assertNull(coordinationFailure.get(), () -> String.valueOf(coordinationFailure.get()));
        assertNotNull(delayedThread.get());
        assertEquals(0, callbackFinished.getCount());
        assertInstanceOf(IllegalStateException.class, delayedFailure.get());
        assertEquals(0, delayedBody.getUnits().stream().filter(this::isProbe).count());
        delayedBody.validate();
    }

    private static boolean awaitLatchDespiteInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                return latch.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                // Deliberately model a worker that finishes its unrelated transform after shutdownNow.
            }
        }
        return latch.getCount() == 0;
    }

    private void assertLegacyInstrumentWorkerExecution(int workers) throws Exception {
        configureSyntheticBodyPacks(workers);
        SootClass first = newClass("example.First", Scene.v().getSootClass("java.lang.Object"));
        SootClass second = newClass("example.Second", Scene.v().getSootClass("java.lang.Object"));
        SootClass excluded = newClass("outside.Excluded", Scene.v().getSootClass("java.lang.Object"));
        first.setApplicationClass();
        second.setApplicationClass();
        excluded.setApplicationClass();
        JimpleBody firstBody = emptyMethod(first, "run");
        JimpleBody secondBody = emptyMethod(second, "run");
        JimpleBody excludedBody = emptyMethod(excluded, "run");
        Set<SootMethod> selected = Set.of(firstBody.getMethod(), secondBody.getMethod());
        Logger logger = Logger.v();
        logger.setTargetPackage("example");
        SootClass helper = Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS);
        Map<SootMethod, List<Unit>> helperUnits = new HashMap<>();
        helper.getMethods().forEach(method -> helperUnits.put(method,
                new ArrayList<>(method.getActiveBody().getUnits())));
        Thread ownerThread = Thread.currentThread();
        Set<Thread> callbackThreads = ConcurrentHashMap.newKeySet();
        Map<SootMethod, List<String>> order = new ConcurrentHashMap<>();
        CyclicBarrier concurrentClasses = workers > 1 ? new CyclicBarrier(2) : null;
        Pack pack = PackManager.v().getPack("jtp");
        pack.add(new Transform("jtp.beforeLogger", new BodyTransformer() {
            @Override
            protected void internalTransform(Body body, String phaseName, Map<String, String> options) {
                if (!selected.contains(body.getMethod())) return;
                assertNotSame(ownerThread, Thread.currentThread());
                callbackThreads.add(Thread.currentThread());
                order.computeIfAbsent(body.getMethod(), ignored -> new ArrayList<>()).add("before");
                assertEquals(0, body.getUnits().stream().filter(LoggerTest.this::isProbe).count());
                assertThrows(IllegalStateException.class, () -> logger.setTargetPackage("changed"));
                if (concurrentClasses != null) {
                    try {
                        concurrentClasses.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    } catch (BrokenBarrierException | TimeoutException failure) {
                        throw new AssertionError("Expected two application classes on distinct Soot workers", failure);
                    }
                }
            }
        }));
        logger.logAllMethods("coverage", true);
        BodyTransformer original = (BodyTransformer) pack.get("jtp.methodsLogger").getTransformer();
        pack.add(new Transform("jtp.afterLogger", new BodyTransformer() {
            @Override
            protected void internalTransform(Body body, String phaseName, Map<String, String> options) {
                if (!selected.contains(body.getMethod())) return;
                order.get(body.getMethod()).add("after");
                assertEquals(1, body.getUnits().stream().filter(LoggerTest.this::isProbe).count());
                assertThrows(IllegalStateException.class, () -> logger.logAllMethods("unexpected", true));
                assertThrows(IllegalStateException.class,
                        () -> logger.addLogToMethod(body.getMethod(), "tag", "unexpected"));
            }
        }));

        logger.instrument();

        assertEquals(workers, callbackThreads.size());
        for (JimpleBody body : List.of(firstBody, secondBody)) {
            assertEquals(List.of("before", "after"), order.get(body.getMethod()));
            assertEquals(1, body.getUnits().stream().filter(this::isProbe).count());
            assertEquals("METHOD=" + body.getMethod().getSignature(), message(body.getUnits().getFirst()));
            body.validate();
        }
        assertEquals(0, excludedBody.getUnits().stream().filter(this::isProbe).count());
        helperUnits.forEach((method, units) -> assertEquals(units,
                new ArrayList<>(method.getActiveBody().getUnits()), "The helper must not instrument itself"));
        assertForeignThreadAccessRejected(logger, original, firstBody);
        assertEquals(1, firstBody.getUnits().stream().filter(this::isProbe).count());
        G.reset();
        assertThrows(IllegalStateException.class, () -> logger.addLogToMethod(firstBody.getMethod(), "tag", "stale"));
        assertThrows(IllegalStateException.class, () -> original.transform(firstBody, "jtp.methodsLogger",
                Collections.singletonMap("enabled", "true")));
    }

    private static void assertForeignThreadAccessRejected(Logger logger, BodyTransformer registered,
                                                         JimpleBody body) throws Exception {
        ExecutorService foreign = Executors.newSingleThreadExecutor();
        try {
            foreign.submit(() -> {
                assertThrows(IllegalStateException.class, () -> logger.setTargetPackage("changed"));
                assertThrows(IllegalStateException.class, logger::instrument);
                assertThrows(IllegalStateException.class,
                        () -> registered.transform(body, "jtp.methodsLogger", Collections.singletonMap("enabled", "true")));
            }).get(10, TimeUnit.SECONDS);
        } finally {
            foreign.shutdownNow();
        }
    }

    private static void configureSyntheticBodyPacks(int workers) {
        Options.v().set_whole_program(false);
        Options.v().set_oaat(false);
        Options.v().set_num_threads(workers);
        Options.v().set_output_format(Options.output_format_none);
        // Keep real worker-backed jtp dispatch, without optimizers that require a complete SDK exception hierarchy.
        for (String phase : List.of("jb.cp", "jb.cbf", "jb.uce", "jb.dae", "jb.cp-ule", "jop", "jap")) {
            Options.v().setPhaseOption(phase, "enabled:false");
        }
    }

    @Test
    void generatedLoggerDeduplicatesConcurrentCallsOnTheJvm() throws Exception {
        Logger.v();
        SootClass helper = Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS);
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(Constants.LOG_CHECKER_CLASS, bytecode(helper));
        classes.put(Constants.ANDROID_UTIL_LOG, bytecode(Scene.v().getSootClass(Constants.ANDROID_UTIL_LOG)));
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> generated = loader.loadClass(Constants.LOG_CHECKER_CLASS);
        Method log = generated.getMethod("log", String.class, String.class);
        Method add = generated.getMethod("addLog", String.class);
        Method contains = generated.getMethod("alreadyLogged", String.class);
        for (Method method : Arrays.asList(log, add, contains)) {
            assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
        }
        for (String signature : Arrays.asList(Constants.SUB_SIG_LOG, Constants.SUB_SIG_ADD_LOG, Constants.SUB_SIG_ALREADY_LOGGED)) {
            Body body = helper.getMethod(signature).retrieveActiveBody();
            assertTrue(body.getUnits().stream().anyMatch(unit -> unit instanceof EnterMonitorStmt));
            assertFalse(body.getTraps().isEmpty());
        }
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CyclicBarrier barrier = new CyclicBarrier(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(workers.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int j = 0; j < 100; j++) {
                        log.invoke(null, "shared-message", "tag");
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
        }
        Class<?> androidLog = loader.loadClass(Constants.ANDROID_UTIL_LOG);
        assertEquals(1, androidLog.getField("calls").getInt(null));
        assertEquals(true, contains.invoke(null, "shared-message"));
        log.invoke(null, "shared-message", "another-tag");
        add.invoke(null, "pre-recorded");
        log.invoke(null, "pre-recorded", "tag");
        assertEquals(1, androidLog.getField("calls").getInt(null));
        log.invoke(null, "new-message", "tag");
        assertEquals(2, androidLog.getField("calls").getInt(null));

        // A failed sink must release the shared lock so a different thread can continue logging.
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> log.invoke(null, "failed-message", null));
        assertEquals(false, contains.invoke(null, "failed-message"));
        ExecutorService recovery = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        try {
            recovery.submit(() -> log.invoke(null, "after-failure", "tag")).get(10, TimeUnit.SECONDS);
        } finally {
            recovery.shutdownNow();
        }
        assertEquals(3, androidLog.getField("calls").getInt(null));
    }

    @Test
    void dexExportRetainsMonitorInstructionsAndExceptionCleanup(@TempDir Path output) throws Exception {
        // DexPrinter needs exception hierarchies, but Soot must not parse the host JDK's class files.
        for (Class<?> type : Arrays.asList(Class.class, RuntimeException.class, ArithmeticException.class,
                ArrayStoreException.class, ClassCastException.class, IllegalMonitorStateException.class,
                IndexOutOfBoundsException.class, ArrayIndexOutOfBoundsException.class, NegativeArraySizeException.class,
                NullPointerException.class, InstantiationError.class, InternalError.class, OutOfMemoryError.class,
                StackOverflowError.class, UnknownError.class, ThreadDeath.class, ClassCircularityError.class,
                ClassFormatError.class, IllegalAccessError.class, IncompatibleClassChangeError.class, LinkageError.class,
                NoClassDefFoundError.class, VerifyError.class, NoSuchFieldError.class, AbstractMethodError.class,
                NoSuchMethodError.class, UnsatisfiedLinkError.class)) {
            installHierarchyStub(type);
        }
        Logger.v();
        Options.v().set_output_format(Options.output_format_dex);
        Options.v().set_output_dir(output.toString());
        DexPrinter printer = new DexPrinter();
        printer.add(Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS));
        printer.print();

        int checked = 0;
        for (ClassDef clazz : DexFileFactory.loadDexFile(output.resolve("classes.dex").toFile(), Opcodes.getDefault()).getClasses()) {
            if (!clazz.getType().equals("L" + Constants.LOG_CHECKER_CLASS + ";")) {
                continue;
            }
            for (com.android.tools.smali.dexlib2.iface.Method method : clazz.getMethods()) {
                if (!Arrays.asList("log", "addLog", "alreadyLogged").contains(method.getName())) {
                    continue;
                }
                assertNotNull(method.getImplementation());
                int enters = 0;
                int exits = 0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction.getOpcode() == Opcode.MONITOR_ENTER) enters++;
                    if (instruction.getOpcode() == Opcode.MONITOR_EXIT) exits++;
                }
                assertEquals(1, enters, method.getName());
                assertTrue(exits >= 2, method.getName() + " must release on normal and exceptional exits");
                assertTrue(method.getImplementation().getTryBlocks().stream().flatMap(block -> block.getExceptionHandlers().stream())
                        .anyMatch(handler -> "Ljava/lang/Throwable;".equals(handler.getExceptionType())));
                checked++;
            }
        }
        assertEquals(3, checked);
    }

    private static SootClass installHierarchyStub(Class<?> type) {
        SootClass existing = Scene.v().getSootClassUnsafe(type.getName(), false);
        if (existing != null) {
            return existing;
        }
        Class<?> parent = type.getSuperclass();
        return newClass(type.getName(), parent == null ? null : installHierarchyStub(parent));
    }

    private static void installPlatformStubs() {
        SootClass object = newClass("java.lang.Object", null);
        object.addMethod(new SootMethod("<init>", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC));
        newClass("java.lang.String", object);
        newClass("java.lang.Throwable", object);
        SootClass set = new SootClass("java.util.Set", Modifier.PUBLIC | Modifier.INTERFACE | Modifier.ABSTRACT);
        Scene.v().addClass(set);
        set.addMethod(new SootMethod("add", Collections.singletonList(object.getType()), BooleanType.v(), Modifier.PUBLIC | Modifier.ABSTRACT));
        set.addMethod(new SootMethod("contains", Collections.singletonList(object.getType()), BooleanType.v(), Modifier.PUBLIC | Modifier.ABSTRACT));
        SootClass hashSet = newClass("java.util.HashSet", object);
        hashSet.addInterface(set);
        hashSet.addMethod(new SootMethod("<init>", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC));

        installAndroidLogStub(object);
    }

    private static void installAndroidLogStub(SootClass object) {
        SootClass androidLog = newClass(Constants.ANDROID_UTIL_LOG, object);
        SootField calls = new SootField("calls", IntType.v(), Modifier.PUBLIC | Modifier.STATIC);
        androidLog.addField(calls);
        SootMethod d = new SootMethod("d", Arrays.asList(RefType.v("java.lang.String"), RefType.v("java.lang.String")),
                IntType.v(), Modifier.PUBLIC | Modifier.STATIC);
        androidLog.addMethod(d);
        JimpleBody body = Jimple.v().newBody(d);
        d.setActiveBody(body);
        Local tag = parameter(body, "tag", RefType.v("java.lang.String"), 0);
        parameter(body, "message", RefType.v("java.lang.String"), 1);
        Local count = Jimple.v().newLocal("count", IntType.v());
        body.getLocals().add(count);
        Unit countCall = Jimple.v().newAssignStmt(count, Jimple.v().newStaticFieldRef(calls.makeRef()));
        body.getUnits().add(Jimple.v().newIfStmt(Jimple.v().newNeExpr(tag, NullConstant.v()), countCall));
        body.getUnits().add(Jimple.v().newThrowStmt(NullConstant.v()));
        body.getUnits().add(countCall);
        body.getUnits().add(Jimple.v().newAssignStmt(count, Jimple.v().newAddExpr(count, IntConstant.v(1))));
        body.getUnits().add(Jimple.v().newAssignStmt(Jimple.v().newStaticFieldRef(calls.makeRef()), count));
        body.getUnits().add(Jimple.v().newReturnStmt(IntConstant.v(0)));
        body.validate();
    }

    private static SootClass newClass(String name, SootClass parent) {
        SootClass result = new SootClass(name, Modifier.PUBLIC);
        if (parent != null) {
            result.setSuperclass(parent);
        }
        Scene.v().addClass(result);
        return result;
    }

    private static JimpleBody emptyMethod(SootClass owner, String name) {
        SootMethod method = new SootMethod(name, Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        JimpleBody body = Jimple.v().newBody(method);
        method.setActiveBody(body);
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        return body;
    }

    private static Local parameter(JimpleBody body, String name, Type type, int index) {
        Local local = Jimple.v().newLocal(name, type);
        body.getLocals().add(local);
        body.getUnits().add(Jimple.v().newIdentityStmt(local, Jimple.v().newParameterRef(type, index)));
        return local;
    }

    private static void apply(String phase, JimpleBody body) {
        ((BodyTransformer) PackManager.v().getPack("jtp").get(phase).getTransformer())
                .transform(body, phase, Collections.singletonMap("enabled", "true"));
    }

    private boolean isProbe(Unit unit) {
        return unit instanceof Stmt && ((Stmt) unit).containsInvokeExpr()
                && ((Stmt) unit).getInvokeExpr().getMethodRef().getDeclaringClass().getName().equals(Constants.LOG_CHECKER_CLASS);
    }

    private String message(Unit probe) {
        assertTrue(isProbe(probe));
        return ((StringConstant) ((Stmt) probe).getInvokeExpr().getArg(0)).value;
    }

    private static byte[] bytecode(SootClass clazz) {
        clazz.getMethods().forEach(method -> method.retrieveActiveBody().validate());
        // Emit these small synthetic bodies directly; optimizer exception analysis requires a full JDK scene.
        Options.v().setPhaseOption("bb", "enabled:false");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new BafASMBackend(clazz, Options.java_version_8).generateClassFile(output);
        return output.toByteArray();
    }
}
