package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.analysis.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable ordered transformations. Each stage completes before the next begins; no global Soot pack is registered. */
public final class TransformationPipeline implements Transformation {
    private final List<Transformation> stages;

    private TransformationPipeline(List<Transformation> stages) {
        this.stages = List.copyOf(stages);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void apply(AnalysisContext context) {
        Objects.requireNonNull(context, "context").checkValid();
        for (Transformation stage : stages) {
            context.checkValid();
            stage.apply(context);
            context.checkValid();
        }
    }

    public static final class Builder {
        private final List<Transformation> stages = new ArrayList<>();

        private Builder() {}

        public Builder add(Transformation stage) {
            stages.add(Objects.requireNonNull(stage, "stage"));
            return this;
        }

        public TransformationPipeline build() {
            return new TransformationPipeline(stages);
        }
    }
}
