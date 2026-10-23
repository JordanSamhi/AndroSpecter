package com.jordansamhi.androspecter.examples;

import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.analysis.InitializationMode;
import com.jordansamhi.androspecter.inspection.Inspection;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Inspects selected DEX classes and method bodies without building an Android call graph. */
public final class InspectClasses {
    private InspectClasses() {}

    /**
     * Opens a class-only analysis and counts methods and invocation sites in the requested
     * application package. No callback discovery or call-graph construction is requested.
     * The session still owns global Soot state and closes on the calling thread.
     *
     * @param args APK path, Android SDK platforms directory, and application package prefix
     * @throws IllegalArgumentException if the arguments or package prefix are invalid
     * @throws RuntimeException if initialization or method-body loading fails
     */
    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: InspectClasses <apk> <platforms> <package-prefix>");
        }
        String prefix = args[2].strip();
        if (prefix.isEmpty() || prefix.equals(".")) {
            throw new IllegalArgumentException("Provide a nonempty application package prefix");
        }
        String classPrefix = prefix.endsWith(".") ? prefix : prefix + ".";
        AtomicInteger methods = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        try (AndroidAnalysis analysis = AndroidAnalysis.builder(Path.of(args[0]), Path.of(args[1]))
                .initializationMode(InitializationMode.CLASSES_ONLY).open()) {
            analysis.inspect(Inspection.builder()
                    .classes(clazz -> clazz.isApplicationClass() && clazz.getName().startsWith(classPrefix))
                    .onMethod(method -> methods.incrementAndGet())
                    .onInvoke((caller, statement, invocation) -> invocations.incrementAndGet())
                    .build());
        }
        System.out.println("Selected methods: " + methods.get());
        System.out.println("Static invocation sites: " + invocations.get());
        System.out.println("Inspection completed without requesting a call graph.");
    }
}
