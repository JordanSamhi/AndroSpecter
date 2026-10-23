package com.jordansamhi.androspecter.result;

/**
 * Completion state reported by a taint analysis. Every state may accompany findings;
 * inspect the state before interpreting an empty finding list as a completed analysis.
 */
public enum AnalysisStatus {
    /** The backend completed without a reported failure; this is not a guarantee of soundness. */
    SUCCESS,
    /** The backend reported a failure or findings could not all be detached; consult diagnostics. */
    FAILED,
    /** A backend data-flow or path-reconstruction time limit was reached; findings may be partial. */
    TIMED_OUT,
    /** Cancellation or interruption was observed; findings may be partial or absent. */
    CANCELLED
}
