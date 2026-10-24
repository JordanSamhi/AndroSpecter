package com.jordansamhi.androspecter.result;

/**
 * Completion state reported by a taint analysis. Every state may accompany findings;
 * inspect the state before interpreting an empty finding list as a completed analysis.
 * The state summarizes observed backend termination and detachment, not runtime
 * exploitability or whether every Android behavior was modeled. For results produced by
 * AndroSpecter, observed cancellation takes precedence over timeout and backend failure;
 * diagnostics can therefore contain more information than the single status value.
 */
public enum AnalysisStatus {
    /** The backend completed without a reported failure; this is not a guarantee of soundness. */
    SUCCESS,
    /** The backend failed, returned no result, or findings could not all be detached; consult diagnostics. */
    FAILED,
    /** A backend data-flow or path-reconstruction time limit was reached; findings may be partial. */
    TIMED_OUT,
    /** Cancellation or interruption was observed; findings may be partial or absent. */
    CANCELLED
}
