package com.jordansamhi.androspecter.analysis;

import java.util.Objects;

/**
 * Immutable common callback-discovery settings, independent of a live backend configuration.
 * Callback timeout is configured separately on {@link AndroidAnalysis.Builder#callbackTimeout(java.time.Duration)}.
 * Disabling discovery or limiting it can remove callback behavior from the resulting model.
 * The settings may be shared across sessions and threads and survive Soot reset. In pinned
 * FlowDroid 2.15.1, callback-count/depth limits and the callback timeout apply to
 * {@link Analyzer#DEFAULT}; {@link Analyzer#FAST} does not apply those limits. Selecting
 * FAST or disabling discovery retains these values but does not enforce them.
 *
 * @param enabled whether FlowDroid should discover Android callbacks
 * @param analyzer non-null callback discovery strategy
 * @param maxCallbacksPerComponent nonnegative DEFAULT-analyzer limit; zero disables it; exceeding
 *                                 a positive limit omits that component's callbacks rather than truncating them
 * @param maxAnalysisDepth DEFAULT-analyzer callback-discovery iteration limit; -1 or zero means unlimited
 */
public record CallbackOptions(boolean enabled, Analyzer analyzer,
                              int maxCallbacksPerComponent, int maxAnalysisDepth) {
    /**
     * Creates validated callback settings without initializing an analysis.
     *
     * @param enabled whether callback discovery is enabled
     * @param analyzer non-null discovery strategy
     * @param maxCallbacksPerComponent nonnegative DEFAULT-analyzer per-component limit, or zero for no limit
     * @param maxAnalysisDepth positive DEFAULT-analyzer iteration limit, or -1/zero for no limit
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
     * a per-component callback limit of 100, and unlimited callback-discovery depth.
     * Components exceeding that limit can have all their callbacks omitted.
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
        /**
         * FlowDroid's fast discovery, which also scans unreachable methods and does not
         * enforce the DEFAULT analyzer's callback-count/depth limits or timeout.
         */
        FAST
    }
}
