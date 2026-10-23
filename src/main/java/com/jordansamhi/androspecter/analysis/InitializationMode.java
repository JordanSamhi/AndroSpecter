package com.jordansamhi.androspecter.analysis;

/** Selects how much work a managed session performs before returning from opening. */
public enum InitializationMode {
    /** Load the APK and construct Android entry points and its call graph immediately; the default. */
    EAGER,
    /**
     * Load APK classes without callback discovery or a call graph. Inspection can run immediately;
     * the first graph request or taint run constructs the Android model using the same Soot scene.
     */
    CLASSES_ONLY
}
