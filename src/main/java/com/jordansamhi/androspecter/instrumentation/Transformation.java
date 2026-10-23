package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.analysis.AnalysisContext;

/** One explicit transformation of the current analysis. Implementations must not reset Soot or retain a stale context. */
@FunctionalInterface
public interface Transformation {
    void apply(AnalysisContext context);
}
