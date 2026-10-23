package com.jordansamhi.androspecter.analysis;

/** Selects FlowDroid's reconstruction of detailed paths for detected source-to-sink flows. */
public enum PathReconstructionMode {
    /** Retain detected flows without requesting detailed reconstructed paths. */
    NONE,
    /** Request FlowDroid's faster path reconstruction strategy. */
    FAST,
    /** Request precise path reconstruction; the managed-session default. */
    PRECISE
}
