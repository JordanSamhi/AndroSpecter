package com.jordansamhi.androspecter.examples;

import com.jordansamhi.androspecter.metadata.ApkInfo;

import java.io.IOException;
import java.nio.file.Path;

/** Prints detached manifest metadata without an Android SDK or a Soot analysis session. */
public final class PrintApkInfo {
    private PrintApkInfo() {}

    /**
     * Reads one APK and prints its declared package, version, SDKs, permissions, and components.
     * Null SDK/exported values are absent or unresolved declarations, not inferred Android defaults.
     *
     * @param args exactly one readable APK path
     * @throws IllegalArgumentException if the argument count is not one
     * @throws IOException if the APK or its binary manifest cannot be read
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: PrintApkInfo <apk>");
        ApkInfo info = ApkInfo.read(Path.of(args[0]));
        System.out.println("Package: " + info.packageName());
        System.out.println("Version: " + info.versionName() + " (" + info.versionCode() + ")");
        System.out.println("Declared min/target SDK: " + info.minSdkVersion() + "/" + info.targetSdkVersion());
        System.out.println("Requested permissions: " + info.permissions());
        for (ApkInfo.Component component : info.components()) {
            System.out.printf("%s %s declaredExported=%s targetActivity=%s authorities=%s%n",
                    component.kind(), component.name(), component.declaredExported(),
                    component.targetActivity(), component.authorities());
        }
    }
}
