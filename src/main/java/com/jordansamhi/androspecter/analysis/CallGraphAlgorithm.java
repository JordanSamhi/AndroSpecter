package com.jordansamhi.androspecter.analysis;

/** Call-graph strategies supported by the AndroSpecter FlowDroid wrapper. */
public enum CallGraphAlgorithm {
    /** Class hierarchy analysis. */
    CHA,
    /** Rapid type analysis, using instantiated types to refine call targets. */
    RTA,
    /** Variable type analysis. */
    VTA,
    /** Soot's Spark points-to based call-graph construction. */
    SPARK
}
