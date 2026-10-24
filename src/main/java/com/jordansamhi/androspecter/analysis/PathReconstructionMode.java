package com.jordansamhi.androspecter.analysis;

/**
 * Selects FlowDroid's reconstruction of detailed paths for detected source-to-sink flows.
 * This does not enable/disable flow detection or guarantee a path for every finding;
 * inspect each finding's {@code pathAvailable} value and the result's completion status.
 */
public enum PathReconstructionMode {
    /** Retain detected flows without requesting detailed reconstructed paths. */
    NONE,
    /** Request FlowDroid's faster path reconstruction strategy. */
    FAST,
    /** Request precise path reconstruction; the managed-session default. */
    PRECISE
}
