package com.jordansamhi.androspecter.analysis;

/** Selects how much work a managed session performs before returning from opening. */
public enum InitializationMode {
    /** Load the APK and construct Android entry points and its call graph immediately; the default. */
    EAGER,
    /**
     * Load and prepare APK classes without callback discovery or a call graph. Inspection can run
     * immediately; selected bodies may be loaded as it runs. The first graph request or taint run
     * constructs the Android model from that scene, with prior transformations as part of its input.
     * This mode still initializes global Soot state and requires exclusive session ownership.
     */
    CLASSES_ONLY
}
