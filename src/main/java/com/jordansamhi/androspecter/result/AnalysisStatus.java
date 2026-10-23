package com.jordansamhi.androspecter.result;

/** Completion state of an analysis. Incomplete analyses may still contain findings. */
public enum AnalysisStatus {
    SUCCESS,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
