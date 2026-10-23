package com.jordansamhi.androspecter.analysis;

import java.util.Objects;

/**
 * Immutable common callback-discovery settings, independent of a live backend configuration.
 * Callback timeout is configured separately on {@link AndroidAnalysis.Builder#callbackTimeout}.
 * Disabling discovery or limiting it can remove callback behavior from the resulting model.
 *
 * @param enabled whether FlowDroid should discover Android callbacks
 * @param analyzer non-null callback discovery strategy
 * @param maxCallbacksPerComponent nonnegative limit; zero disables the limit; exceeding a positive
 *                                 limit makes FlowDroid omit that component's callbacks
 * @param maxAnalysisDepth callback-registration depth; -1 or zero means unlimited, positive values limit depth
 */
public record CallbackOptions(boolean enabled, Analyzer analyzer,
                              int maxCallbacksPerComponent, int maxAnalysisDepth) {
    /**
     * Creates validated callback settings without initializing an analysis.
     *
     * @param enabled whether callback discovery is enabled
     * @param analyzer non-null discovery strategy
     * @param maxCallbacksPerComponent nonnegative per-component limit, or zero for no limit
     * @param maxAnalysisDepth positive depth limit, or -1/zero for no limit
     * @throws NullPointerException if {@code analyzer} is null
     * @throws IllegalArgumentException if the callback limit is negative or depth is less than -1
     */
    public CallbackOptions {
        Objects.requireNonNull(analyzer, "analyzer");
        if (maxCallbacksPerComponent < 0 || maxAnalysisDepth < -1) {
            throw new IllegalArgumentException("Callback limit must be nonnegative and depth must be at least -1");
        }
    }

    /**
     * Returns the callback defaults of the supported FlowDroid version: enabled, default analyzer,
     * at most 100 callbacks per component, and unlimited callback-registration depth.
     *
     * @return immutable default callback settings
     */
    public static CallbackOptions defaults() {
        return new CallbackOptions(true, Analyzer.DEFAULT, 100, -1);
    }

    /** Callback discovery strategies supported by the managed API. */
    public enum Analyzer {
        /** FlowDroid's default iterative callback discovery. */
        DEFAULT,
        /** FlowDroid's fast callback discovery, with different precision/runtime tradeoffs. */
        FAST
    }
}
