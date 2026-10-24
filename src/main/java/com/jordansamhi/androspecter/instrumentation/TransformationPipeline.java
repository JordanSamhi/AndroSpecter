package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.analysis.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered transformation registrations. Each stage completes before the next begins, so later
 * stages observe earlier mutations. No global Soot pack is registered. Stage instances are retained, not
 * cloned, and may themselves be stateful or bound to one analysis context.
 */
public final class TransformationPipeline implements Transformation {
    private final List<Transformation> stages;

    private TransformationPipeline(List<Transformation> stages) {
        this.stages = List.copyOf(stages);
    }

    /**
     * Creates a builder with no transformation stages.
     *
     * @return a new mutable pipeline builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Applies the registered stages in order, checking the context before and after each stage.
     * An empty pipeline only validates the context. A failed or cancelled stage prevents subsequent stages
     * from running; changes already made are not rolled back. This method does not close the context.
     * Calling it again runs all stages again; idempotence depends on the individual stages.
     * Direct application does not notify a managed analysis's graph cache. Submit the pipeline to
     * {@link com.jordansamhi.androspecter.analysis.AndroidAnalysis#apply(Transformation)} when using that facade.
     *
     * @param context valid context for the current scene
     * @throws NullPointerException if {@code context} is null
     * @throws IllegalStateException if the context is closed, belongs to another thread, or Soot was reset
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if a stage fails
     */
    @Override
    public void apply(AnalysisContext context) {
        Objects.requireNonNull(context, "context").checkValid();
        for (Transformation stage : stages) {
            context.checkValid();
            stage.apply(context);
            context.checkValid();
        }
    }

    /** Mutable stage registration builder; each build copies the current ordered stage list. */
    public static final class Builder {
        private final List<Transformation> stages = new ArrayList<>();

        private Builder() {}

        /**
         * Appends a stage to run after all previously registered stages.
         * Registering the same instance twice runs it twice when the pipeline is applied.
         *
         * @param stage transformation to retain in the pipeline
         * @return this builder
         * @throws NullPointerException if {@code stage} is null
         */
        public Builder add(Transformation stage) {
            stages.add(Objects.requireNonNull(stage, "stage"));
            return this;
        }

        /**
         * Copies the ordered stage registrations without executing them.
         * Later builder changes do not affect the result; stage instances remain shared.
         *
         * @return a new pipeline
         */
        public TransformationPipeline build() {
            return new TransformationPipeline(stages);
        }
    }
}
