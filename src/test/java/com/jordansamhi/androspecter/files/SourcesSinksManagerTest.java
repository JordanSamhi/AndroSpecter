package com.jordansamhi.androspecter.files;

import com.jordansamhi.androspecter.MethodSignatureConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.jimple.infoflow.android.data.AndroidMethod;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourcesSinksManagerTest {

    private SourcesSinksManager manager;

    @BeforeEach
    void setUp() {
        manager = SourcesSinksManager.v();
    }

    @Test
    void testSingleton() {
        SourcesSinksManager anotherInstance = SourcesSinksManager.v();
        assertSame(manager, anotherInstance);
    }

    @Test
    void testLoadSourcesSinksFromFile() throws IOException {
        File file = File.createTempFile("testSourcesSinks", ".txt");
        PrintWriter writer = new PrintWriter(file);
        String method = "<com.test.Class: void method(int,java.lang.String)>";
        String anotherMethod = "<com.test.Class2: java.lang.String anotherMethod()>";

        writer.println("SOURCE|" + method);
        writer.println("SINK|" + anotherMethod);
        writer.close();

        manager.loadSourcesSinksFromFile(file);

        Set<AndroidMethod> sources = manager.getSources();
        Set<AndroidMethod> sinks = manager.getSinks();

        MethodSignatureConverter msc = MethodSignatureConverter.v();
        AndroidMethod expectedSource = new AndroidMethod(msc.getMethodNameFromJimpleSignature(method),
                msc.getParametersNamesFromJimpleSignature(method),
                msc.getReturnNameFromJimpleSignature(method),
                msc.getClassNameFromJimpleSignature(method));

        AndroidMethod expectedSink = new AndroidMethod(msc.getMethodNameFromJimpleSignature(anotherMethod),
                msc.getParametersNamesFromJimpleSignature(anotherMethod),
                msc.getReturnNameFromJimpleSignature(anotherMethod),
                msc.getClassNameFromJimpleSignature(anotherMethod));

        assertTrue(sources.contains(expectedSource), "Expected source method not found");
        assertTrue(sinks.contains(expectedSink), "Expected sink method not found");

        file.delete();
    }
}