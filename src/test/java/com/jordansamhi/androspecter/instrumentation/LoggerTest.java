package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.utils.Constants;
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
import java.util.*;
import java.util.concurrent.*;

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
