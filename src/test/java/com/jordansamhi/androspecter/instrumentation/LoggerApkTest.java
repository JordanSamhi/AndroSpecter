package com.jordansamhi.androspecter.instrumentation;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.analysis.InitializationMode;
import com.jordansamhi.androspecter.utils.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import soot.Body;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.options.Options;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real-APK checks of legacy worker dispatch and DEX output; opt in with the existing SDK property. */
@EnabledIfSystemProperty(named = "androspecter.androidPlatforms", matches = ".+")
@Timeout(120)
class LoggerApkTest {
    private static final String APP_CLASS = "demo.taint.MainActivity";
    private static final String TAG = "legacy-apk-coverage";
    private static final Set<String> APP_METHODS = Set.of("<init>", "onCreate", "source", "sink");
    private static final String HELPER_DEX_TYPE = "L" + Constants.LOG_CHECKER_CLASS.replace('.', '/') + ";";

    @TempDir Path directory;

    @Test
    void instrumentsRealApkWithOneSootWorker() throws Exception {
        instrumentFixture(1, false);
    }

    @Test
    void instrumentsAndExportsRealApkWithTwoSootWorkers() throws Exception {
        instrumentFixture(2, true);
    }

    private void instrumentFixture(int workers, boolean export) throws Exception {
        Path inputApk = directory.resolve("fixture.apk");
        try (InputStream input = getClass().getResourceAsStream("/apks/managed-analysis.apk")) {
            assertNotNull(input, "The reproducible APK fixture must be on the test classpath");
            Files.copy(input, inputApk);
        }
        Path platforms = Path.of(System.getProperty("androspecter.androidPlatforms"));
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(inputApk, platforms)
                .initializationMode(InitializationMode.CLASSES_ONLY).open()) {
            assertFalse(Scene.v().hasCallGraph());
            Options.v().set_whole_program(false);
            Options.v().set_num_threads(workers);
            Options.v().set_output_format(Options.output_format_dex);
            // Keep normal Soot cleanup/optimization phases enabled: they run on real DEX bodies here.
            Logger logger = Logger.v();
            logger.setTargetPackage("demo.taint");
            logger.logAllMethods(TAG, true);
            logger.instrument();

            Set<String> instrumented = new HashSet<>();
            for (SootMethod method : Scene.v().getSootClass(APP_CLASS).getMethods()) {
                if (!method.isConcrete()) continue;
                Body body = method.retrieveActiveBody();
                body.validate();
                List<InvokeExpr> probes = body.getUnits().stream().filter(this::isProbe)
                        .map(unit -> ((Stmt) unit).getInvokeExpr()).toList();
                assertEquals(1, probes.size(), method.getSignature());
                assertEquals("METHOD=" + method.getSignature(), ((StringConstant) probes.get(0).getArg(0)).value);
                assertEquals(TAG, ((StringConstant) probes.get(0).getArg(1)).value);
                instrumented.add(method.getName());
            }
            assertEquals(APP_METHODS, instrumented);

            SootClass helper = Scene.v().getSootClass(Constants.LOG_CHECKER_CLASS);
            for (SootMethod method : helper.getMethods()) {
                Body body = method.retrieveActiveBody();
                body.validate();
                assertTrue(body.getUnits().stream().noneMatch(this::isProbe),
                        "The generated helper must not recursively invoke log: " + method.getSignature());
            }

            if (export) {
                Path output = directory.resolve("instrumented");
                logger.exportNewApk(output.toString());
                Path outputApk = output.resolve(inputApk.getFileName());
                assertTrue(Files.isRegularFile(outputApk), "DEX export should preserve the APK container");
                assertExportedProbes(outputApk);
            }
        }
    }

    private boolean isProbe(Unit unit) {
        if (!(unit instanceof Stmt statement) || !statement.containsInvokeExpr()) return false;
        var reference = statement.getInvokeExpr().getMethodRef();
        return reference.getDeclaringClass().getName().equals(Constants.LOG_CHECKER_CLASS)
                && reference.getSubSignature().toString().equals(Constants.SUB_SIG_LOG);
    }

    private void assertExportedProbes(Path apk) throws Exception {
        var container = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.getDefault());
        Set<String> instrumented = new HashSet<>();
        boolean helperFound = false;
        for (String entryName : container.getDexEntryNames()) {
            var entry = container.getEntry(entryName);
            assertNotNull(entry);
            for (ClassDef clazz : entry.getDexFile().getClasses()) {
                boolean app = clazz.getType().equals("Ldemo/taint/MainActivity;");
                boolean helper = clazz.getType().equals(HELPER_DEX_TYPE);
                if (!app && !helper) continue;
                helperFound |= helper;
                for (Method method : clazz.getMethods()) {
                    assertNotNull(method.getImplementation(), method.getName());
                    int probes = 0;
                    for (Instruction instruction : method.getImplementation().getInstructions()) {
                        if (instruction instanceof ReferenceInstruction reference
                                && reference.getReference() instanceof MethodReference target
                                && target.getDefiningClass().equals(HELPER_DEX_TYPE)
                                && target.getName().equals("log") && target.getReturnType().equals("V")
                                && target.getParameterTypes().stream().map(CharSequence::toString).toList()
                                .equals(List.of("Ljava/lang/String;", "Ljava/lang/String;"))) {
                            probes++;
                        }
                    }
                    assertEquals(app ? 1 : 0, probes, clazz.getType() + "->" + method.getName());
                    if (app) instrumented.add(method.getName());
                }
            }
        }
        assertTrue(helperFound, "The generated helper must be present in the emitted APK");
        assertEquals(APP_METHODS, instrumented, "Every fixture method must retain its probe in DEX");
    }
}
