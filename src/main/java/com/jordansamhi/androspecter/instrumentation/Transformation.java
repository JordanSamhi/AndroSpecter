package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.analysis.AnalysisContext;

/**
 * One explicit transformation of the current analysis's live Soot scene.
 * Implementations must not reset Soot, close the supplied context, or use its objects on another thread.
 * For managed analyses, submit transformations through
 * {@link com.jordansamhi.androspecter.analysis.AndroidAnalysis#apply(Transformation)} so cached graphs are invalidated.
 * A context validates scene lifetime and cancellation; it does not acquire exclusive Soot ownership on its own.
 */
@FunctionalInterface
public interface Transformation {
    /**
     * Mutates the current scene synchronously on the context's owning thread.
     * Implementations should call {@link AnalysisContext#checkValid()} before accessing Soot and around
     * user callbacks or long loops, preserve valid bodies, and propagate failures. Soot objects are borrowed
     * for the context's lifetime; copying a reference does not detach it. No rollback is implied on failure.
     *
     * @param context valid context for the scene to transform; ownership stays with the caller
     * @throws NullPointerException if {@code context} is null
     * @throws IllegalStateException if a context validity check detects closure, reset, or the wrong thread
     * @throws java.util.concurrent.CancellationException if cooperative cancellation or interruption is observed
     * @throws RuntimeException if the transformation fails, potentially after partial mutation
     */
    void apply(AnalysisContext context);
}
