package com.jordansamhi.androspecter.files;

import com.jordansamhi.androspecter.MethodSignatureConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soot.jimple.infoflow.android.data.AndroidMethod;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SourcesSinksManagerTest {

    private SourcesSinksManager manager;
    @TempDir
    Path tempDirectory;

    @BeforeEach
    void setUp() {
        manager = SourcesSinksManager.v();
        manager.reset();
    }

    @AfterEach
    void tearDown() {
        manager.reset();
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

    private File writeFile(String name, String contents) throws IOException {
        Path path = tempDirectory.resolve(name);
        Files.write(path, contents.getBytes(StandardCharsets.UTF_8));
        return path.toFile();
    }

    @Test
    void acceptsWhitespaceCommentsConstructorsArraysAndAdditiveLoads() throws IOException {
        Set<AndroidMethod> defaults = new HashSet<>(manager.getSources());
        manager.loadSourcesSinksFromFile(writeFile("first.txt",
                "  # comment\n\n SOURCE | <example.Échantillon: void <init>(int[], java.lang.String[][])> \n"));
        manager.loadSourcesSinksFromFile(writeFile("second.txt",
                "SINK|<example.Consumer: void use(java.lang.String[])>\n"));

        assertTrue(manager.getSources().containsAll(defaults));
        assertTrue(manager.getSources().stream().anyMatch(method -> method.getSignature().equals(
                "<example.Échantillon: void <init>(int[],java.lang.String[][])>")));
        assertTrue(manager.getSinks().stream().anyMatch(method -> method.getSignature().equals(
                "<example.Consumer: void use(java.lang.String[])>")));
        manager.reset();
        assertEquals(defaults, manager.getSources());
        assertFalse(manager.getSinks().stream().anyMatch(method -> method.getClassName().equals("example.Consumer")));
    }

    @Test
    void malformedRecordsReportFileAndLineWithoutPartialApplication() throws IOException {
        Set<AndroidMethod> sources = new HashSet<>(manager.getSources());
        Set<AndroidMethod> sinks = new HashSet<>(manager.getSinks());
        String[] malformed = {
                "UNKNOWN|<example.C: void m()>",
                "SOURCE|not-a-signature",
                "SINK|<example.C: void m(int,)>",
                "SOURCE|<example.C: void m()>|extra",
                "SOURCE|"
        };
        for (int i = 0; i < malformed.length; i++) {
            File file = writeFile("invalid-" + i + ".txt",
                    "SOURCE|<example.Partial: void source()>\n" + malformed[i] + "\n");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> manager.loadSourcesSinksFromFile(file));
            assertTrue(error.getMessage().contains(file + ":2"));
            assertTrue(error.getMessage().contains(malformed[i]));
            assertEquals(sources, manager.getSources());
            assertEquals(sinks, manager.getSinks());
        }
    }

    @Test
    void unreadableFileIsReportedInsteadOfSilentlyIgnored() {
        File file = tempDirectory.resolve("missing.txt").toFile();
        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> manager.loadSourcesSinksFromFile(file));
        assertTrue(error.getMessage().contains(file.toString()));
    }
}
